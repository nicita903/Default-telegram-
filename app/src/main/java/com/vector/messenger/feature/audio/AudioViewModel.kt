package com.vector.messenger.feature.audio

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.*
import com.vector.core.media.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

val LocalAudioViewModel = staticCompositionLocalOf<AudioViewModel?> { null }

@HiltViewModel
class AudioViewModel @Inject constructor(val engine: SharedMediaPlayer, private val files: MediaRepository) : ViewModel() {
    val state = engine.state
    private var pending: Job? = null
    init {
        viewModelScope.launch { files.state.map { it.session }.distinctUntilChanged().collect { pending?.cancel(); engine.clearTelegramAudio() } }
        viewModelScope.launch { state.map { it.item == null }.distinctUntilChanged().collect { if (it) pending?.cancel() } }
    }
    fun observe(item: MediaItem): Flow<AudioPlaybackState> = state.map {
        if (it.item?.chatId == item.chatId && it.item.messageId == item.messageId) it else AudioPlaybackState()
    }.distinctUntilChanged()
    fun toggle(item: MediaItem) {
        val current = state.value
        if (current.item?.chatId == item.chatId && current.item.messageId == item.messageId && current.phase in listOf(AudioPhase.Playing, AudioPhase.Loading)) {
            pending?.cancel(); engine.pause()
            if (current.phase == AudioPhase.Loading) engine.stop(false)
            return
        }
        pending?.cancel(); engine.loading(item)
        val session = files.state.value.session
        files.request(item)
        pending = viewModelScope.launch {
            try {
                val ready = withTimeout(90_000) { files.state.first { snapshot ->
                    !snapshot.authenticated || snapshot.session != session || snapshot.error != null ||
                        snapshot.files[item.media.file.id]?.let { it.complete && it.path != null } == true
                } }
                ensureActive()
                if (!ready.authenticated || ready.session != session) { engine.stop(); return@launch }
                val path = ready.files[item.media.file.id]?.path
                if (state.value.item?.chatId != item.chatId || state.value.item?.messageId != item.messageId) return@launch
                if (path == null) engine.failure(item, "Audio is unavailable. Check the download and retry.") else engine.voice(item, path)
            } catch (cancelled: TimeoutCancellationException) { engine.failure(item, "Download is taking longer than expected. Retry when ready.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { engine.failure(item, "Unable to play audio. Retry.") }
        }
    }
    fun stop() { pending?.cancel(); pending = null; engine.stop() }
    fun background() { pending?.cancel(); pending = null; if (!engine.musicActive) engine.stop(false) }
    fun seek(fraction: Float) = engine.seek(fraction)
    fun speed() = engine.speed(when (state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f })
    override fun onCleared() { pending?.cancel(); if (!engine.musicActive) engine.stop() }
}
