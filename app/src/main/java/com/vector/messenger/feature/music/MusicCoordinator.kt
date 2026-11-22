package com.vector.messenger.feature.music

import android.content.*
import androidx.core.content.ContextCompat
import com.vector.core.music.*
import com.vector.messenger.feature.audio.*
import com.vector.messenger.feature.search.MusicLibraryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Singleton
class MusicCoordinator @Inject constructor(@ApplicationContext private val context: Context,
    val engine: SharedMediaPlayer, val library: MusicLibraryRepository, private val scope: CoroutineScope) {
    val state = engine.musicState
    private var preparation: Job? = null
    private val errorState = MutableStateFlow<String?>(null)
    val error = errorState.asStateFlow()
    init {
        // ResolvingDataSource invokes this on its loader thread, never the UI thread.
        engine.privateMusicResolver = { track -> runBlocking(Dispatchers.IO) { library.resolve(track) } }
        scope.launch {
            var lastKey: String? = null
            var savedAt = 0L
            var lastSaved: MusicQueue? = null
            state.collect { current ->
                if (current.current != null && current.phase == AudioPhase.Playing && current.current!!.key != lastKey) {
                    lastKey = current.current!!.key; library.played(lastKey!!)
                }
                val now = android.os.SystemClock.elapsedRealtime()
                val changed = lastSaved?.copy(position = 0) != current.queue.copy(position = 0)
                if (current.tracks.isNotEmpty() && (current.phase != AudioPhase.Playing || changed || now - savedAt > 15000)) {
                    savedAt = now; lastSaved = current.queue; library.queue(current.queue)
                }
            }
        }
        scope.launch {
            state.map { it.tracks.isNotEmpty() }.distinctUntilChanged().collectLatest { relevant ->
                if (relevant) library.library.collect { catalogue ->
                    engine.prunePrivateMusic(catalogue.songs.filter { it.telegram != null }.mapTo(mutableSetOf()) { it.key })
                    val current = state.value.current
                    if (current != null && engine.musicActive && catalogue.songs.none { it.key == current.key }) {
                        errorState.value = "The current track is no longer available. Check permission or its download."
                        library.queue(state.value.queue); engine.stopMusic()
                    }
                }
            }
        }
    }
    fun play(tracks: List<MusicTrack>, key: String, shuffle: Boolean = false, restore: MusicQueue? = null) {
        preparation?.cancel(); errorState.value = null
        preparation = scope.launch {
            val requested = tracks.distinctBy { it.key }.take(1000)
            val selected = if (shuffle && restore == null) requested.randomOrNull() ?: return@launch else requested.find { it.key == key } ?: return@launch
            val resolved = library.resolve(selected)
            if (resolved == null) { errorState.value = "Track unavailable. Check audio permission or its Telegram download."; return@launch }
            val safe = requested.map { if (it.key == selected.key) it.copy(uri = resolved) else it }
            val queue = (restore ?: MusicQueue()).copy(keys = safe.map { it.key }, index = safe.indexOfFirst { it.key == selected.key }, shuffle = restore?.shuffle ?: shuffle)
            engine.music(safe, queue)
            try { ContextCompat.startForegroundService(context, Intent(context, MusicPlaybackService::class.java)) }
            catch (_: Exception) { engine.stopMusic(); errorState.value = "Playback could not enter foreground mode. Open Music and retry." }
        }
    }
    fun toggle() {
        if (state.value.phase == AudioPhase.Playing) engine.musicPause()
        else if (engine.musicActive) {
            engine.musicResume()
            runCatching { ContextCompat.startForegroundService(context, Intent(context, MusicPlaybackService::class.java)) }
        } else {
            val saved = state.value.queue.takeIf { it.keys.isNotEmpty() } ?: library.settings.value.queue
            val tracks = library.library.value.songs.associateBy { it.key }
            val restored = restoreMusicQueue(saved, tracks.keys)
            val available = restored.keys.mapNotNull(tracks::get)
            val current = available.getOrNull(restored.index) ?: return
            play(available, current.key, restore = if (state.value.phase == AudioPhase.Complete || (current.duration > 0 && restored.position >= current.duration)) restored.copy(position = 0) else restored)
        }
    }
    fun stop() { preparation?.cancel(); library.queue(state.value.queue); engine.stopMusic(); context.stopService(Intent(context, MusicPlaybackService::class.java)) }
    fun next(previous: Boolean = false) = engine.musicNext(previous)
    fun seek(value: Float) = engine.musicSeek(value)
    fun shuffle() { if (state.value.tracks.isEmpty()) library.queue(library.settings.value.queue.let { it.copy(shuffle = !it.shuffle) }) else engine.musicShuffle() }
    fun repeat() { if (state.value.tracks.isEmpty()) library.queue(library.settings.value.queue.let { it.copy(repeat = MusicRepeat.entries[(it.repeat.ordinal + 1) % 3]) }) else engine.musicRepeat() }
    fun speed(value: Float) { if (state.value.tracks.isEmpty()) library.queue(library.settings.value.queue.copy(speed = value)) else engine.musicSpeed(value) }
    fun move(from: Int, to: Int) { if (state.value.tracks.isEmpty()) library.queue(library.settings.value.queue.move(from, to)) else engine.musicMove(from, to) }
}
