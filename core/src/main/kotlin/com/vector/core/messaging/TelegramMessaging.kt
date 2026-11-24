package com.vector.core.messaging

import com.vector.core.*
import com.vector.core.profile.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ChatPosition(val archived: Boolean, val order: Long, val pinned: Boolean)
data class TelegramChat(val chat: Chat, val positions: List<ChatPosition>, val lastMessageId: Long = 0, val lastReadOutbox: Long = 0,
    val peerId: Long = 0, val supergroup: Boolean = false, val lastRestricted: Boolean = false)
sealed interface ChatUpdate {
    data class Snapshot(val value: TelegramChat) : ChatUpdate
    data class Position(val id: Long, val value: ChatPosition) : ChatUpdate
    data class Positions(val id: Long, val values: List<ChatPosition>) : ChatUpdate
    data class Preview(val id: Long, val text: String, val time: String, val lastId: Long, val positions: List<ChatPosition>, val restricted: Boolean = false) : ChatUpdate
    data class Title(val id: Long, val title: String) : ChatUpdate
    data class Photo(val id: Long, val photo: ProfilePhoto?) : ChatUpdate
    data class Unread(val id: Long, val count: Int) : ChatUpdate
    data class ReadOutbox(val id: Long, val lastId: Long) : ChatUpdate
    data class Permissions(val id: Long, val canSend: Boolean, val canSendPhotos: Boolean = canSend, val canSendDocuments: Boolean = canSend) : ChatUpdate
    data class Protection(val id: Long, val protected: Boolean) : ChatUpdate
    data class Upsert(val message: Message, val oldId: Long? = null, val isNew: Boolean = false) : ChatUpdate
    data class Content(val chatId: Long, val messageId: Long, val text: String, val media: com.vector.core.media.TelegramMedia? = null) : ChatUpdate
    data class Deleted(val chatId: Long, val ids: List<Long>) : ChatUpdate
    data class Reactions(val chatId: Long, val messageId: Long, val reactions: List<MessageReaction>) : ChatUpdate
    data class File(val photo: ProfilePhoto) : ChatUpdate
    data class Access(val peerId: Long, val supergroup: Boolean, val restricted: Boolean, val canSendOverride: Boolean? = null, val displayName: String? = null,
        val canSendPhotosOverride: Boolean? = canSendOverride) : ChatUpdate
}
enum class MessagingFailure { Network, Unavailable, Forbidden }
class MessagingException(val failure: MessagingFailure) : Exception("Telegram request failed")

interface TelegramMessagingClient {
    val updates: Flow<ChatUpdate>
    val connection: StateFlow<ProfileConnection>
    suspend fun loadChats(archived: Boolean, limit: Int): Boolean
    suspend fun history(chatId: Long, fromId: Long, limit: Int): List<Message>
    suspend fun sendText(chatId: Long, text: String, replyTo: Long?): Message
    suspend fun editText(chatId: Long, messageId: Long, text: String): Message = throw MessagingException(MessagingFailure.Unavailable)
    suspend fun sendPhotos(chatId: Long, photos: List<com.vector.core.media.UploadPhoto>, caption: String, replyTo: Long?): List<Message> = throw MessagingException(MessagingFailure.Unavailable)
    suspend fun photoPermission(chatId: Long, document: Boolean = false): Boolean = true
    suspend fun cancelPhotoSend(chatId: Long, messageId: Long) { throw MessagingException(MessagingFailure.Unavailable) }
    suspend fun toggleReaction(chatId: Long, messageId: Long, emoji: String): List<MessageReaction> = throw MessagingException(MessagingFailure.Unavailable)
    suspend fun sendVideoNote(chatId: Long, clip: com.vector.core.media.VideoNoteClip, replyTo: Long?): Message = throw MessagingException(MessagingFailure.Unavailable)
    suspend fun sendVoice(chatId: Long, clip: com.vector.core.audio.VoiceClip, replyTo: Long?): Message = throw MessagingException(MessagingFailure.Unavailable)
    suspend fun resend(chatId: Long, messageId: Long): List<Message>
    suspend fun openChat(chatId: Long)
    suspend fun closeChat(chatId: Long)
    suspend fun view(chatId: Long, ids: List<Long>)
    suspend fun pin(chatId: Long, archived: Boolean, pinned: Boolean)
    suspend fun archive(chatId: Long, archived: Boolean)
    suspend fun downloadAvatar(fileId: Int): ProfilePhoto
}
