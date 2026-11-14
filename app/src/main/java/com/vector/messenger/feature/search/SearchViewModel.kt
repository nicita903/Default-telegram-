package com.vector.messenger.feature.search

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.*

enum class SearchScope { Chats, Music }
fun searchLocked(query: String, automatic: Boolean) = automatic && query.isNotEmpty()

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val saved: SavedStateHandle, repository: ChatRepository, private val music: MusicLibraryRepository,
) : ViewModel() {
    val chatQuery = saved.getStateFlow("chatQuery", "")
    val musicQuery = saved.getStateFlow("musicQuery", "")
    val chats = repository.chats
    val messages = repository.messages
    val status = repository.status
    private val recentState = MutableStateFlow<List<String>>(emptyList())
    val recents = recentState.asStateFlow()
    val library = music.library.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), MusicLibrary())
    fun query(scope: SearchScope, value: String) { saved[if (scope == SearchScope.Chats) "chatQuery" else "musicQuery"] = value }
    fun rememberQuery(value: String) { if (value.isNotBlank()) recentState.value = (listOf(value.trim()) + recentState.value).distinct().take(8) }
    fun clearRecent() { recentState.value = emptyList() }
    fun refreshMusic() = music.refresh()
}
