package com.vector.core

import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    val chats: StateFlow<List<Chat>>
    val messages: StateFlow<List<Message>>
    suspend fun send(chatId: Long, text: String): SendResult
    fun markRead(chatId: Long)
    fun toggleArchive(chatId: Long)
    fun togglePin(chatId: Long)
}

interface GhostRepository {
    val preferences: StateFlow<Map<Long, GhostPreferences>>
    fun update(contactId: Long, preferences: GhostPreferences)
}
