package com.vector.core

data class Chat(
    val id: Long, val title: String, val preview: String, val time: String,
    val unread: Int = 0, val pinned: Boolean = false, val archived: Boolean = false,
    val isContact: Boolean = true,
)
enum class Delivery { Received, LocalDemo }
data class Message(val id: Long, val chatId: Long, val text: String, val time: String, val delivery: Delivery)
data class GhostPreferences(
    val suppressTyping: Boolean = false, val suppressRecording: Boolean = false,
    val hideOnline: Boolean = false, val hideLastSeen: Boolean = false,
)
enum class ThemeMode { System, Dark, Light }
enum class ChatFilter { All, Unread, Archived }

fun filterChats(chats: List<Chat>, query: String, filter: ChatFilter): List<Chat> = chats
    .filter { chat ->
        chat.title.contains(query.trim(), ignoreCase = true) && when (filter) {
            ChatFilter.All -> !chat.archived
            ChatFilter.Unread -> !chat.archived && chat.unread > 0
            ChatFilter.Archived -> chat.archived
        }
    }.sortedByDescending { it.pinned }

sealed interface SendResult {
    data object Sent : SendResult
    data object Empty : SendResult
    data object TooLong : SendResult
    data object MissingChat : SendResult
}
