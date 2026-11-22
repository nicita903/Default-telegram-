package com.vector.core

data class Chat(
    val id: Long, val title: String, val preview: String, val time: String,
    val unread: Int = 0, val pinned: Boolean = false, val archived: Boolean = false,
    val isContact: Boolean = true,
    val kind: ChatKind = if (isContact) ChatKind.Contact else ChatKind.Group,
    val photo: com.vector.core.profile.ProfilePhoto? = null,
    val canSend: Boolean = true, val protectedContent: Boolean = false,
    val order: Long = 0,
    val canSendPhotos: Boolean = canSend,
) { override fun toString() = "Chat([redacted])" }
enum class ChatKind { Contact, Group, Channel }
enum class Delivery { Received, LocalDemo, Sending, Sent, Read, Failed }
data class MessageReaction(val emoji: String, val count: Int, val chosen: Boolean = false)
data class Attachment(val name: String, val uri: String, val mimeType: String) { override fun toString() = "Attachment([redacted])" }
data class Message(
    val id: Long, val chatId: Long, val text: String, val time: String, val delivery: Delivery,
    val replyTo: Long? = null, val reaction: String? = null, val attachment: Attachment? = null,
    val date: Long = 0, val outgoing: Boolean = delivery != Delivery.Received,
    val canRetry: Boolean = false, val retryAt: Long = 0, val canSave: Boolean = true, val restricted: Boolean = false,
    val senderId: Long = 0, val senderName: String? = null,
    val media: com.vector.core.media.TelegramMedia? = null,
    val reactions: List<MessageReaction> = emptyList(), val isService: Boolean = false, val senderIsChat: Boolean = false,
    val mediaAlbumId: Long = 0,
) { override fun toString() = "Message([redacted])" }
data class GhostPreferences(
    val suppressTyping: Boolean = false, val suppressRecording: Boolean = false,
    val hideOnline: Boolean = false, val hideLastSeen: Boolean = false,
)
enum class ChatFilter { All, Unread, Groups, Channels, Archived }

fun filterChats(chats: List<Chat>, query: String, filter: ChatFilter): List<Chat> = chats
    .filter { chat ->
        chat.title.contains(query.trim(), ignoreCase = true) && when (filter) {
            ChatFilter.All -> !chat.archived
            ChatFilter.Unread -> !chat.archived && chat.unread > 0
            ChatFilter.Groups -> !chat.archived && chat.kind == ChatKind.Group
            ChatFilter.Channels -> !chat.archived && chat.kind == ChatKind.Channel
            ChatFilter.Archived -> chat.archived
        }
    }.let { result -> if (chats.any { it.order != 0L }) result else result.sortedByDescending { it.pinned } }

sealed interface SendResult {
    data object Sent : SendResult
    data object Empty : SendResult
    data object TooLong : SendResult
    data object MissingChat : SendResult
    data object Failed : SendResult
    data object Unsupported : SendResult
    data object Forbidden : SendResult
    data object Uncertain : SendResult
}
