package com.vector.core

import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val messages: StateFlow<List<Message>>
    suspend fun send(chatId: Long, text: String, replyTo: Long? = null, attachment: Attachment? = null): SendResult
    suspend fun editText(chatId: Long, messageId: Long, text: String): SendResult = SendResult.Unsupported
    suspend fun sendPhotos(chatId: Long, photos: List<com.vector.core.media.UploadPhoto>, caption: String, album: Boolean, replyTo: Long?): com.vector.core.media.PhotoSendResult = com.vector.core.media.PhotoSendResult(result = SendResult.Unsupported)
    fun cancelPhotoSend(chatId: Long, messageId: Long) {}
    suspend fun sendVideoNote(chatId: Long, clip: com.vector.core.media.VideoNoteClip, replyTo: Long?): com.vector.core.media.VideoNoteSubmission = com.vector.core.media.VideoNoteSubmission(SendResult.Unsupported)
    val outgoingUpdates: kotlinx.coroutines.flow.Flow<Message> get() = kotlinx.coroutines.flow.emptyFlow()
    suspend fun sendVoice(chatId: Long, clip: com.vector.core.audio.VoiceClip, replyTo: Long? = null): SendResult = SendResult.Unsupported
    fun toggleReaction(chatId: Long, messageId: Long, reaction: String)
    fun markRead(chatId: Long)
    fun toggleArchive(chatId: Long)
    fun togglePin(chatId: Long)
    val status: StateFlow<ChatStatus> get() = DefaultChatState.status
    val history: StateFlow<HistoryState> get() = DefaultChatState.history
    val feedback: kotlinx.coroutines.flow.Flow<ChatFeedback> get() = kotlinx.coroutines.flow.emptyFlow()
    val arrivals: kotlinx.coroutines.flow.Flow<MessageArrival> get() = kotlinx.coroutines.flow.emptyFlow()
    fun reportViewport(chatId: Long, nearLatest: Boolean, scrolling: Boolean) {}
    val reactionError: StateFlow<String?> get() = DefaultChatState.reactionError
    fun loadMoreChats(archived: Boolean = false) {}
    fun openConversation(chatId: Long) {}
    fun closeConversation(chatId: Long) {}
    fun loadOlder(chatId: Long) {}
    fun viewMessages(chatId: Long, messageIds: List<Long>) {}
    fun retry(chatId: Long, messageId: Long) {}
    fun avatar(chatId: Long) {}
    fun latest(chatId: Long) {}
}

data class ChatStatus(val live: Boolean = false, val session: Long = 0, val loading: Boolean = false,
    val mainMore: Boolean = false, val archiveMore: Boolean = false, val error: String? = null,
    val connection: com.vector.core.profile.ProfileConnection = com.vector.core.profile.ProfileConnection.Connecting)
data class HistoryState(val chatId: Long? = null, val loading: Boolean = false, val hasMore: Boolean = true, val error: String? = null, val atLatest: Boolean = true)
private object DefaultChatState {
    val reactionError = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    val status = kotlinx.coroutines.flow.MutableStateFlow(ChatStatus())
    val history = kotlinx.coroutines.flow.MutableStateFlow(HistoryState())
}

interface GhostRepository {
    val preferences: StateFlow<Map<Long, GhostPreferences>>
    fun update(contactId: Long, preferences: GhostPreferences)
    val state: StateFlow<com.vector.core.ghost.GhostState> get() = DefaultGhostState.state
    fun enabled(value: Boolean) {}
    fun select(userId: Long) {}
    fun remove(userId: Long) {}
    fun policy(userId: Long, value: com.vector.core.ghost.ContactPrivacy) {}
    fun refresh() {}
    fun applyPrivacy() {}
    fun useCurrentRules() {}
    fun sendAction(chatId: Long, action: String) {}
}
private object DefaultGhostState { val state = kotlinx.coroutines.flow.MutableStateFlow(com.vector.core.ghost.GhostState()) }
