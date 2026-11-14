package com.vector.core.demo

import com.vector.core.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

/** In-memory fixtures only. Never bind these repositories to a Telegram session. */
class DemoChatRepository : ChatRepository {
    private val nextId = AtomicLong(100)
    private val chatState = MutableStateFlow(listOf(
        Chat(1, "Alex Morgan", "The photos look great. Thank you!", "12:42", 2, pinned = true),
        Chat(2, "Mira Chen", "Coffee tomorrow?", "11:18", 1),
        Chat(3, "Design studio", "Lea: I've shared the new layouts", "10:36", 5, isContact = false),
        Chat(4, "Listening room", "A few discoveries for the weekend", "Yesterday", isContact = false, kind = ChatKind.Channel),
        Chat(5, "Daniel", "See you on Saturday", "Mon"),
        Chat(6, "Old project", "Everything is saved here", "Oct 24", archived = true, isContact = false),
    ))
    private val messageState = MutableStateFlow(chatState.value.flatMap { chat -> listOf(
        Message(chat.id * 10, chat.id, "Hi! This conversation uses sample messages.", "12:40", Delivery.Received),
        Message(chat.id * 10 + 1, chat.id, "I can try the composer here.", "12:41", Delivery.LocalDemo),
        Message(chat.id * 10 + 2, chat.id, chat.preview, chat.time, Delivery.Received),
    ) })
    override val chats = chatState.asStateFlow()
    override val messages = messageState.asStateFlow()

    override suspend fun send(chatId: Long, text: String, replyTo: Long?, attachment: Attachment?): SendResult {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && attachment == null) return SendResult.Empty
        if (trimmed.length > 4096) return SendResult.TooLong
        if (chatState.value.none { it.id == chatId }) return SendResult.MissingChat
        val reply = replyTo?.takeIf { id -> messageState.value.any { it.id == id && it.chatId == chatId } }
        val message = Message(nextId.incrementAndGet(), chatId, trimmed, "Now", Delivery.LocalDemo, replyTo = reply, attachment = attachment)
        messageState.update { messages ->
            // Bound each demonstration conversation; this is not durable message storage.
            messages.filter { it.chatId != chatId } + (messages.filter { it.chatId == chatId } + message).takeLast(100)
        }
        chatState.update { list -> list.map { if (it.id == chatId) it.copy(preview = trimmed.ifEmpty { attachment?.name.orEmpty() }, time = "Now") else it } }
        return SendResult.Sent
    }
    override fun toggleReaction(chatId: Long, messageId: Long, reaction: String) {
        if (reaction !in setOf("👍", "❤", "✨")) return
        messageState.update { list -> list.map {
            if (it.chatId == chatId && it.id == messageId) it.copy(reaction = reaction.takeUnless { value -> value == it.reaction }) else it
        } }
    }
    override fun markRead(chatId: Long) = change(chatId) { it.copy(unread = 0) }
    override fun toggleArchive(chatId: Long) = change(chatId) { it.copy(archived = !it.archived) }
    override fun togglePin(chatId: Long) = change(chatId) { it.copy(pinned = !it.pinned) }
    private fun change(id: Long, transform: (Chat) -> Chat) {
        chatState.update { list -> list.map { if (it.id == id) transform(it) else it } }
    }
}

/** Local UI demonstration; settings reset on process death and never change Telegram privacy. */
class DemoGhostRepository : GhostRepository {
    private val preferenceState = MutableStateFlow<Map<Long, GhostPreferences>>(emptyMap())
    override val preferences = preferenceState.asStateFlow()
    override fun update(contactId: Long, preferences: GhostPreferences) {
        preferenceState.update { it + (contactId to preferences) }
    }
}
