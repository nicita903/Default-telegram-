package com.vector.messenger.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import com.vector.core.media.*
import com.vector.messenger.feature.media.PhotoUploadStore

@HiltViewModel
class ChatsViewModel @Inject constructor(private val repository: ChatRepository, private val photoStore: PhotoUploadStore? = null,
    val photoGallery: com.vector.messenger.feature.media.LocalPhotoGallery? = null,
    private val activity: com.vector.messenger.telegram.TelegramConversationActivity? = null, private val ghost: GhostRepository? = null) : ViewModel() {
    internal val messageStatusTransitions = MessageStatusTransitions()
    val arrivals = repository.arrivals
    private val submittedEvents = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val submitted = submittedEvents.asSharedFlow()
    fun requestLatest(chatId: Long) { submittedEvents.tryEmit(chatId) }
    internal val scrollBookmarks = linkedMapOf<Pair<Long, Long>, ConversationBookmark>()
    fun viewport(chatId: Long, nearLatest: Boolean, scrolling: Boolean) = repository.reportViewport(chatId, nearLatest, scrolling)
    fun subtitle(chatId: Long, peer: Long?, kind: ChatKind) = activity?.observe(chatId, peer, kind) ?: flowOf(ConversationSubtitle())
    fun observePresence(peer: Long?) { activity?.ensure(peer) }
    private var typingChat: Long? = null
    private var lastTyping = 0L
    private var typingIdle: kotlinx.coroutines.Job? = null
    fun composing(chatId: Long, text: String) {
        if (!status.value.live || text.isBlank()) { stopTyping(chatId); return }
        if (typingChat != null && typingChat != chatId) stopTyping(typingChat!!)
        val now = android.os.SystemClock.elapsedRealtime()
        if (typingChat != chatId || now - lastTyping >= 4000) {
            typingChat = chatId; lastTyping = now
            ghost?.sendAction(chatId, "chatActionTyping")
        }
        typingIdle?.cancel()
        typingIdle = viewModelScope.launch { kotlinx.coroutines.delay(5000); stopTyping(chatId) }
    }
    fun stopTyping(chatId: Long, notify: Boolean = true) {
        if (typingChat != chatId) return
        typingChat = null; typingIdle?.cancel(); typingIdle = null; lastTyping = 0
        if (notify) ghost?.sendAction(chatId, "chatActionCancel")
    }
    val chats = repository.chats
    val messages = repository.messages
    val status = repository.status
    val history = repository.history
    init {
        viewModelScope.launch {
            status.map { it.session }.distinctUntilChanged().collect {
                typingIdle?.cancel(); typingIdle = null; typingChat = null; lastTyping = 0
                scrollBookmarks.clear()
            }
        }
    }
    fun enter(id: Long) = repository.openConversation(id)
    fun leave(id: Long) { stopTyping(id); repository.closeConversation(id) }
    fun newer(id: Long) = repository.loadNewer(id)
    fun older(id: Long) = repository.loadOlder(id)
    fun visible(id: Long, ids: List<Long>) = repository.viewMessages(id, ids)
    fun retry(id: Long, messageId: Long) = repository.retry(id, messageId)
    fun moreChats() = repository.loadMoreChats(filter.value == ChatFilter.Archived)
    fun avatar(id: Long) = repository.avatar(id)
    fun latest(id: Long) = repository.latest(id)
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ChatFilter.All)
    val search = query.asStateFlow()
    val selectedFilter = filter.asStateFlow()
    val filteredChats = combine(chats, query, filter, ::filterChats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val errorState = MutableStateFlow<String?>(null)
    val error = combine(errorState, repository.reactionError) { sendError, reactionError -> sendError ?: reactionError }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    private val pending = MutableStateFlow<Set<Long>>(emptySet())
    val submitting = pending.asStateFlow()
    private val photoErrorState = MutableStateFlow<String?>(null)
    val photoError = photoErrorState.asStateFlow()
    fun clearPhotoError() { photoErrorState.value = null }
    fun releasePhotoSelection(uris: List<String>) { viewModelScope.launch { photoStore?.release(uris) } }
    fun cancelPhotoSend(chatId: Long, messageId: Long) = repository.cancelPhotoSend(chatId, messageId)
    suspend fun photoPreview(uri: String, edge: Int = 512) = photoStore?.preview(uri, status.value.session, edge)
    fun sendPhotos(chatId: Long, uris: List<String>, caption: String, album: Boolean, replyTo: Long?, quality: PhotoQuality = PhotoQuality.STANDARD, onResult: (PhotoSendResult) -> Unit) {
        if (chatId in pending.value) return
        val store = photoStore ?: return
        if (caption.length > 1024) { photoErrorState.value = "Use a caption of 1,024 characters or fewer."; return }
        if (uris.size !in 1..100 || !status.value.live) { photoErrorState.value = "Select up to 100 images in a signed-in Telegram conversation."; return }
        val session = status.value.session
        pending.value += chatId
        photoErrorState.value = null
        viewModelScope.launch {
            val prepared = mutableListOf<UploadPhoto>()
            var handedOff = false
            try {
                for (uri in uris) {
                    if (status.value.session != session || !status.value.live) return@launch
                    prepared += store.prepare(uri, quality)
                }
                if (status.value.session != session || !status.value.live) return@launch
                handedOff = true
                val result = repository.sendPhotos(chatId, prepared, caption, album, replyTo)
                if (status.value.session != session) return@launch
                // An ambiguous native send may still be uploading. Retain its source and block
                // automatic resubmission; only definitely rejected inputs can be discarded.
                if (result.result != SendResult.Uncertain) store.discard(prepared.drop(result.accepted))
                photoErrorState.value = when (result.result) {
                    SendResult.Sent -> null
                    SendResult.Uncertain -> "Send status is unknown. Check the conversation before sending these photos again."
                    SendResult.Forbidden -> "Telegram does not allow photo posting in this conversation."
                    SendResult.TooLong -> "Use a caption of 1,024 characters or fewer."
                    else -> "Some photos could not be submitted. Unsent photos are retained here for retry."
                }
                if (result.accepted > 0) requestLatest(chatId)
                onResult(result)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (status.value.session == session) photoErrorState.value = "Unable to prepare these photos. Remove unreadable images or select them again." }
            catch (_: OutOfMemoryError) { if (status.value.session == session) photoErrorState.value = "Not enough memory to prepare this image. Try a smaller photo." }
            finally {
                if (!handedOff) withContext(NonCancellable) { store.discard(prepared) }
                pending.value -= chatId
            }
        }
    }
    data class ChatTool(val chatId: Long, val name: String)
    private val toolState = MutableStateFlow<ChatTool?>(null)
    val chatTool = toolState.asStateFlow()
    fun showChatTool(chatId: Long, name: String) { toolState.value = ChatTool(chatId, name) }
    fun consumeChatTool(tool: ChatTool) { if (toolState.value == tool) toolState.value = null }
    fun search(value: String) { query.value = value }
    fun filter(value: ChatFilter) { filter.value = value }
    fun open(id: Long) = repository.markRead(id)
    fun archive(id: Long) = repository.toggleArchive(id)
    fun pin(id: Long) = repository.togglePin(id)
    fun react(chatId: Long, messageId: Long, reaction: String) = repository.toggleReaction(chatId, messageId, reaction)
    fun send(id: Long, text: String, replyTo: Long? = null, attachment: Attachment? = null, onSent: () -> Unit) {
        submit(id, editing = false, operation = { repository.send(id, text, replyTo, attachment) }, onSuccess = onSent)
    }
    fun edit(id: Long, messageId: Long, text: String, onSaved: () -> Unit) {
        submit(id, editing = true, operation = { repository.editText(id, messageId, text) }, onSuccess = onSaved)
    }
    private fun submit(id: Long, editing: Boolean, operation: suspend () -> SendResult, onSuccess: () -> Unit) {
        // Set before launching: two taps in the same frame cannot submit twice.
        if (id in pending.value) return
        pending.value += id
        errorState.value = null
        viewModelScope.launch {
            try {
                when (operation()) {
                    SendResult.Sent -> { errorState.value = null; if (!editing) requestLatest(id); onSuccess() }
                    SendResult.Empty -> errorState.value = "Write a message first."
                    SendResult.TooLong -> errorState.value = "Use 4,096 characters or fewer."
                    SendResult.MissingChat -> errorState.value = "This conversation is unavailable."
                    SendResult.Failed -> errorState.value = if (editing) "Telegram could not save this edit. Your changes are retained." else "Telegram could not send this message. Try again."
                    SendResult.Unsupported -> errorState.value = "Only text messages are supported for Telegram in this phase."
                    SendResult.Forbidden -> errorState.value = if (editing) "This message cannot be edited." else "You cannot send messages in this conversation."
                    SendResult.Uncertain -> errorState.value = "Send status is unknown. Check the conversation before sending again."
                }
            } finally { pending.value -= id }
        }
    }
}
