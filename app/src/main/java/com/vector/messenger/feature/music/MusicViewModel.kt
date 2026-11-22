package com.vector.messenger.feature.music

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.*
import com.vector.core.music.*
import com.vector.messenger.feature.search.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

val LocalMusicViewModel = staticCompositionLocalOf<MusicViewModel?> { null }
data class MusicFilter(val section: String, val collection: String? = null, val query: String = "", val sort: String = "Title")

@HiltViewModel
class MusicViewModel @Inject constructor(val repository: MusicLibraryRepository, val player: MusicCoordinator) : ViewModel() {
    val library = repository.library
    val playback = player.state
    val error = player.error
    private val modal = MutableStateFlow(false)
    val nowPlaying = modal.asStateFlow()
    val restoredQueueTracks = combine(library, repository.settings.map { it.queue.keys }.distinctUntilChanged()) { catalogue, keys -> catalogue.songs to keys }
        .mapLatest { (songs, keys) -> withContext(Dispatchers.Default) { val byKey = songs.associateBy { it.key }; keys.mapNotNull(byKey::get) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun openPlayer() { modal.value = true }
    fun closePlayer() { modal.value = false }
    fun refresh() = repository.refresh()
    fun play(tracks: List<MusicTrack>, key: String, shuffle: Boolean = false) { player.play(tracks, key, shuffle); openPlayer() }
    fun results(filter: Flow<MusicFilter>): Flow<List<MusicTrack>> = combine(library, filter) { lib, value -> lib to value }
        .mapLatest { (lib, value) -> withContext(Dispatchers.Default) { musicResults(lib, value) } }
    fun groups(filter: Flow<MusicFilter>): Flow<List<Pair<String, String>>> = combine(library, filter) { lib, value -> lib to value }
        .mapLatest { (lib, value) -> withContext(Dispatchers.Default) { musicGroups(lib, value) } }
}

fun musicGroups(library: MusicLibrary, filter: MusicFilter): List<Pair<String, String>> = when (filter.section) {
    "Playlists" -> library.playlists.map { it.id to it.name }
    "Albums" -> library.songs.map { it.album }.distinct().map { it to it.ifBlank { "Album unavailable" } }
    "Artists" -> library.songs.map { it.artist }.distinct().map { it to it.ifBlank { "Artist unavailable" } }
    "Folders" -> library.songs.map { it.folder }.distinct().map { it to it }
    else -> emptyList()
}.filter { it.second.contains(filter.query.trim(), true) }.sortedBy { it.second.lowercase() }

fun musicResults(library: MusicLibrary, filter: MusicFilter): List<MusicTrack> {
    val byKey = library.songs.associateBy { it.key }
    val base = when (filter.section) {
        "Recently played" -> library.recent.mapNotNull(byKey::get)
        "Playlists" -> library.playlists.find { it.id == filter.collection }?.keys?.mapNotNull(byKey::get).orEmpty()
        "Albums" -> library.songs.filter { it.album == filter.collection }
        "Artists" -> library.songs.filter { it.artist == filter.collection }
        "Folders" -> library.songs.filter { it.folder == filter.collection }
        "Telegram music" -> library.songs.filter { it.telegram != null }
        else -> library.songs
    }
    val searched = filterMusic(base, filter.query)
    return when {
        filter.section == "Recently played" || filter.section == "Playlists" -> searched
        filter.section in listOf("Recently added", "Recently Added") || filter.sort == "Newest" -> searched.sortedByDescending { it.added }
        filter.sort == "Artist" -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist })
        filter.sort == "Duration" -> searched.sortedBy { it.duration }
        filter.sort == "Track" -> searched.sortedBy { it.number }
        else -> searched.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
    }
}
