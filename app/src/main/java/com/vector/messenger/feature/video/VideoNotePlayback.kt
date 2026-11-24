package com.vector.messenger.feature.video

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import com.vector.messenger.feature.audio.SharedMediaPlayer
import com.vector.messenger.feature.audio.AudioPhase
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class VideoNotePlaybackState(val key: String = "", val player: ExoPlayer? = null, val playing: Boolean = false,
    val muted: Boolean = true, val position: Long = 0, val duration: Long = 0, val automatic: Boolean = false, val failed: Boolean = false)

/** One active inline decoder. Muted autoplay never takes audio focus or stops music. */
@Singleton
class VideoNotePlayback @Inject constructor(@ApplicationContext private val context: Context, private val engine: SharedMediaPlayer,
    private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(VideoNotePlaybackState())
    val state = mutable.asStateFlow()
    private var ticker: Job? = null
    private val viewerState = MutableStateFlow(false)
    val viewerOpen = viewerState.asStateFlow()
    fun viewer(open: Boolean) { if (open) stop(); viewerState.value = open }
    fun seek(position: Long) { state.value.player?.let { it.seekTo(position.coerceIn(0, it.duration.coerceAtLeast(0))) } }
    init { scope.launch { combine(engine.state, engine.musicState) { audio, music -> audio.phase == AudioPhase.Playing || music.phase == AudioPhase.Playing }.distinctUntilChanged().collect { playing ->
        if (playing && !state.value.muted) pause()
    } } }
    fun start(key: String, path: String, automatic: Boolean) {
        if (state.value.key == key && state.value.player != null) return
        stop()
        val player = ExoPlayer.Builder(context).build()
        player.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), !automatic)
        if (!automatic) engine.stop()
        player.volume = if (automatic) 0f else 1f
        mutable.value = VideoNotePlaybackState(key, player, muted = automatic, automatic = automatic)
        fun publish() { if (mutable.value.player === player) mutable.update { it.copy(playing = player.isPlaying, position = player.currentPosition.coerceAtLeast(0), duration = player.duration.coerceAtLeast(0), failed = player.playerError != null) } }
        player.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) { publish()
                ticker?.cancel()
                if (player.isPlaying) ticker = scope.launch { while (isActive && mutable.value.player === player) { publish(); delay(100) } }
            }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(java.io.File(path)))); player.prepare(); player.play()
    }
    fun toggle() { val p = state.value.player ?: return; mutable.update { it.copy(automatic = false) }
        if (p.isPlaying) p.pause() else { if (p.playbackState == Player.STATE_ENDED) p.seekTo(0); if (!state.value.muted) engine.stop(); p.play() } }
    fun mute() { val p = state.value.player ?: return; val muted = !state.value.muted
        if (!muted) engine.stop()
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), !muted)
        p.volume = if (muted) 0f else 1f; mutable.update { it.copy(muted = muted, automatic = false) }
    }
    fun pause() { state.value.player?.pause() }
    fun stop(key: String? = null) {
        if (key != null && state.value.key != key) return
        ticker?.cancel(); ticker = null; state.value.player?.release(); mutable.value = VideoNotePlaybackState()
    }
}
