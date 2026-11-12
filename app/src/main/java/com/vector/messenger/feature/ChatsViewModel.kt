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
    private val query = MutableStateFlow("")
    private val filter = MutableStateFlow(ChatFilter.All)
    val search = query.asStateFlow()
    val selectedFilter = filter.asStateFlow()
    val filteredChats = combine(chats, query, filter, ::filterChats)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val errorState = MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    fun search(value: String) { query.value = value }
    fun filter(value: ChatFilter) { filter.value = value }
    fun open(id: Long) = repository.markRead(id)
    fun archive(id: Long) = repository.toggleArchive(id)
    fun pin(id: Long) = repository.togglePin(id)
    fun react(chatId: Long, messageId: Long, reaction: String) = repository.toggleReaction(chatId, messageId, reaction)
    fun send(id: Long, text: String, replyTo: Long? = null, attachment: Attachment? = null, onSent: () -> Unit) {
        viewModelScope.launch {
            when (repository.send(id, text, replyTo, attachment)) {
                SendResult.Sent -> { errorState.value = null; onSent() }
                SendResult.Empty -> errorState.value = "Write a message first."
                SendResult.TooLong -> errorState.value = "Use 4,096 characters or fewer."
                SendResult.MissingChat -> errorState.value = "This conversation is unavailable."
            }
        }
    }
}
