package com.vector.core

import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val messages: StateFlow<List<Message>>
    suspend fun send(chatId: Long, text: String, replyTo: Long? = null, attachment: Attachment? = null): SendResult
    fun toggleReaction(chatId: Long, messageId: Long, reaction: String)
    fun markRead(chatId: Long)
    fun toggleArchive(chatId: Long)
    fun togglePin(chatId: Long)
}

interface GhostRepository {
    val preferences: StateFlow<Map<Long, GhostPreferences>>
    fun update(contactId: Long, preferences: GhostPreferences)
}
