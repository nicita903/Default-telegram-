package com.vector.messenger.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@HiltViewModel
class ChatsViewModel @Inject constructor(private val repository: ChatRepository) : ViewModel() {
    val chats = repository.chats
    val messages = repository.messages
    val status = repository.status
    val history = repository.history
    fun enter(id: Long) = repository.openConversation(id)
    fun leave(id: Long) = repository.closeConversation(id)
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
    val error = errorState.asStateFlow()
    private val pending = MutableStateFlow<Set<Long>>(emptySet())
    val submitting = pending.asStateFlow()
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
                    SendResult.Sent -> { errorState.value = null; onSuccess() }
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
