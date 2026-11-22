package com.vector.messenger.feature.audio

import android.content.Context
import android.net.Uri
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import com.vector.core.media.MediaItem as TelegramMediaItem
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.vector.core.music.*
import com.vector.messenger.feature.music.MusicPlaybackState
import androidx.media3.datasource.*
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

enum class AudioPhase { Idle, Loading, Playing, Paused, Complete, Failed }
data class AudioPlaybackState(val item: TelegramMediaItem? = null, val phase: AudioPhase = AudioPhase.Idle,
    val position: Long = 0, val duration: Long = 0, val speed: Float = 1f, val error: String? = null) {
    override fun toString() = "AudioPlaybackState(phase=$phase)"
}

/** Sole Media3 player factory. Voice rows never construct players; video acquires the same engine. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SharedMediaPlayer(private val context: Context, private val scope: CoroutineScope) {
    private var player: ExoPlayer? = null
    private var ticker: Job? = null
    private val positions = linkedMapOf<Pair<Long, Long>, Long>()
    private val mutable = MutableStateFlow(AudioPlaybackState())
    val state = mutable.asStateFlow()
    private val musicMutable = MutableStateFlow(MusicPlaybackState())
    val musicState = musicMutable.asStateFlow()
    private val musicPlayerMutable = MutableStateFlow<ExoPlayer?>(null)
    val musicPlayer = musicPlayerMutable.asStateFlow()
    val musicActive get() = musicPlayerMutable.value != null
    private val musicSources = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val sourceTracks = java.util.concurrent.ConcurrentHashMap<String, MusicTrack>()
    var privateMusicResolver: ((MusicTrack) -> String?)? = null
    fun owns(value: ExoPlayer) = player === value
    private fun create(speech: Boolean, music: Boolean = false): ExoPlayer = ExoPlayer.Builder(context.applicationContext)
        .setMediaSourceFactory(DefaultMediaSourceFactory(ResolvingDataSource.Factory(DefaultDataSource.Factory(context)) { spec ->
            if (spec.uri.scheme == "default-music") {
                val alias = spec.uri.toString()
                val track = sourceTracks[alias] ?: throw java.io.IOException("Music source unavailable")
                val source = if (track.telegram != null) privateMusicResolver?.invoke(track) else musicSources[alias]
                spec.withUri(Uri.parse(source ?: throw java.io.IOException("Music source unavailable")))
            } else spec
        }))
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(2000, 15000, 500, 1000).setTargetBufferBytes(16 * 1024 * 1024).build())
        .build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(if (speech) C.AUDIO_CONTENT_TYPE_SPEECH else if (music) C.AUDIO_CONTENT_TYPE_MUSIC else C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
    private fun releaseCurrent() {
        parkMusic()
        ticker?.cancel(); ticker = null
        val item = mutable.value.item
        if (item != null && player != null) {
            positions[item.chatId to item.messageId] = player!!.currentPosition
            while (positions.size > 16) positions.remove(positions.keys.first())
        }
        player?.release(); player = null
    }
    fun video(path: String, position: Long, looping: Boolean, muted: Boolean): ExoPlayer {
        releaseCurrent(); mutable.value = AudioPlaybackState(speed = mutable.value.speed)
        return create(false).also {
            player = it; it.repeatMode = if (looping) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            it.volume = if (muted) 0f else 1f
            it.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); it.seekTo(position); it.prepare()
        }
    }
    fun releaseVideo(value: ExoPlayer) { if (owns(value)) releaseCurrent() }
    fun loading(item: TelegramMediaItem) {
        val completed = mutable.value.phase == AudioPhase.Complete && mutable.value.item?.chatId == item.chatId && mutable.value.item?.messageId == item.messageId
        releaseCurrent()
        if (completed) positions[item.chatId to item.messageId] = 0
        mutable.value = AudioPlaybackState(item, AudioPhase.Loading, duration = item.media.duration * 1000L, speed = mutable.value.speed)
    }
    fun failure(item: TelegramMediaItem, message: String) {
        releaseCurrent(); mutable.value = AudioPlaybackState(item, AudioPhase.Failed, duration = item.media.duration * 1000L, speed = mutable.value.speed, error = message)
    }
    fun voice(item: TelegramMediaItem, path: String) {
        releaseCurrent()
        val speed = mutable.value.speed
        val position = positions[item.chatId to item.messageId] ?: 0
        mutable.value = AudioPlaybackState(item, AudioPhase.Loading, position, item.media.duration * 1000L, speed)
        val audio = create(true); player = audio
        fun publish() {
            if (player !== audio) return
            mutable.update { old -> old.copy(position = audio.currentPosition.coerceAtLeast(0),
                duration = audio.duration.takeIf { it > 0 } ?: item.media.duration * 1000L,
                phase = when { audio.playerError != null -> AudioPhase.Failed; audio.playbackState == Player.STATE_ENDED -> AudioPhase.Complete
                    audio.isPlaying -> AudioPhase.Playing; audio.playbackState == Player.STATE_BUFFERING -> AudioPhase.Loading; else -> AudioPhase.Paused }) }
        }
        audio.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) { mutable.update { it.copy(phase = AudioPhase.Failed, error = "Audio playback failed. Retry or download again.") } }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish(); ticker?.cancel()
                if (isPlaying) ticker = scope.launch { while (isActive && audio === this@SharedMediaPlayer.player) { publish(); delay(100) } }
            }
        })
        audio.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path)))); audio.setPlaybackSpeed(speed)
        audio.seekTo(position); audio.prepare(); audio.play()
    }
    fun pause() { player?.pause() }
    fun seek(fraction: Float) {
        val current = player ?: return
        val duration = current.duration.takeIf { it > 0 } ?: mutable.value.duration
        current.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
        mutable.update { it.copy(position = current.currentPosition.coerceAtLeast(0)) }
    }
    fun speed(value: Float) { if (value !in listOf(1f, 1.5f, 2f)) return; player?.setPlaybackSpeed(value); mutable.update { it.copy(speed = value) } }
    fun stop(clear: Boolean = true) {
        val position = player?.currentPosition ?: mutable.value.position
        val complete = mutable.value.phase == AudioPhase.Complete
        releaseCurrent()
        mutable.value = if (clear) AudioPlaybackState(speed = mutable.value.speed) else mutable.value.copy(phase = if (complete) AudioPhase.Complete else AudioPhase.Paused, position = position)
    }
    fun reset() { stop(); positions.clear() }

    /** Park, don't resume, when voice/video/recording takes ownership. Queue and position survive. */
    private fun parkMusic() {
        val active = musicPlayerMutable.value ?: return
        val parked = com.vector.core.music.parkMusic(musicMutable.value.queue, object : MusicInterruptionSource {
            override val index get() = active.currentMediaItemIndex.coerceAtLeast(0)
            override val position get() = active.currentPosition.coerceAtLeast(0)
            override fun pause() { active.pause() }
        })
        musicMutable.update { old -> old.copy(queue = parked.queue, phase = AudioPhase.Paused, interrupted = parked.requiresManualResume) }
        musicPlayerMutable.value = null
    }
    fun clearTelegramAudio() {
        positions.clear()
        if (state.value.item != null) stop()
        prunePrivateMusic(emptySet())
    }
    fun prunePrivateMusic(allowed: Set<String>) {
        val music = musicState.value
        val removed = music.tracks.filter { it.telegram != null && it.key !in allowed }.mapTo(mutableSetOf()) { it.key }
        if (music.current?.key in removed) stopMusic()
        else if (removed.isNotEmpty()) {
            val key = music.current?.key
            val keep = music.tracks.filter { it.key !in removed }
            musicMutable.value = music.copy(tracks = keep, queue = music.queue.copy(keys = keep.map { it.key }, index = keep.indexOfFirst { it.key == key }.coerceAtLeast(0)))
            music.tracks.indices.reversed().filter { music.tracks[it].key in removed }.forEach { musicPlayerMutable.value?.removeMediaItem(it) }
            val aliases = sourceTracks.filterValues { it.key in removed }.keys
            aliases.forEach { musicSources.remove(it); sourceTracks.remove(it) }
        }
    }
    fun music(tracks: List<MusicTrack>, saved: MusicQueue) {
        if (tracks.isEmpty()) return
        releaseCurrent(); mutable.value = AudioPlaybackState(speed = mutable.value.speed)
        musicSources.clear(); sourceTracks.clear()
        val queue = saved.copy(keys = tracks.map { it.key }).normalized()
        musicMutable.value = MusicPlaybackState(tracks, queue, AudioPhase.Loading)
        val audio = create(false, true); player = audio; musicPlayerMutable.value = audio
        val items = tracks.map { track ->
            val alias = Uri.Builder().scheme("default-music").authority("track").appendPath(track.key).build()
            musicSources[alias.toString()] = track.uri
            sourceTracks[alias.toString()] = track
            MediaItem.Builder().setMediaId(track.key).setUri(alias).setMimeType(track.mime.takeIf(String::isNotBlank))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist.takeIf(String::isNotBlank))
                    .setAlbumTitle(track.album.takeIf(String::isNotBlank)).setArtworkUri(track.artwork?.let(Uri::parse)).build()).build()
        }
        fun publish() {
            if (musicPlayerMutable.value !== audio) return
            musicMutable.update { old -> old.copy(queue = old.queue.copy(index = audio.currentMediaItemIndex.coerceAtLeast(0),
                position = audio.currentPosition.coerceAtLeast(0), shuffle = audio.shuffleModeEnabled,
                repeat = when (audio.repeatMode) { Player.REPEAT_MODE_ONE -> MusicRepeat.One; Player.REPEAT_MODE_ALL -> MusicRepeat.All; else -> MusicRepeat.Off },
                speed = audio.playbackParameters.speed), duration = audio.duration.takeIf { it > 0 } ?: old.current?.duration ?: 0,
                phase = when { audio.playerError != null -> AudioPhase.Failed; audio.playbackState == Player.STATE_ENDED -> AudioPhase.Complete
                    audio.isPlaying -> AudioPhase.Playing; audio.playbackState == Player.STATE_BUFFERING -> AudioPhase.Loading; else -> AudioPhase.Paused }) }
        }
        audio.addListener(object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) = publish()
            override fun onPlayerError(error: PlaybackException) { musicMutable.update { it.copy(phase = AudioPhase.Failed, error = "Track unavailable or unsupported. Choose Next or another track.") } }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                publish(); ticker?.cancel()
                if (isPlaying) ticker = scope.launch { while (isActive && musicPlayerMutable.value === audio) { publish(); delay(250) } }
            }
        })
        audio.setMediaItems(items, queue.index.coerceIn(tracks.indices), queue.position)
        audio.setShuffleOrder(androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder(musicShuffleOrder(tracks.size, queue.index, System.nanoTime()), System.nanoTime()))
        audio.shuffleModeEnabled = queue.shuffle
        audio.repeatMode = when (queue.repeat) { MusicRepeat.Off -> Player.REPEAT_MODE_OFF; MusicRepeat.One -> Player.REPEAT_MODE_ONE; MusicRepeat.All -> Player.REPEAT_MODE_ALL }
        audio.setPlaybackSpeed(queue.speed); audio.prepare(); audio.play()
    }
    fun musicPause() { musicPlayerMutable.value?.pause() }
    fun musicResume() { musicPlayerMutable.value?.let { if (it.playbackState == Player.STATE_ENDED) it.seekTo(0); it.prepare(); it.play() } }
    fun musicNext(previous: Boolean = false) { musicPlayerMutable.value?.let { if (previous) it.seekToPreviousMediaItem() else it.seekToNextMediaItem(); it.prepare(); it.play() } }
    fun musicSeek(fraction: Float) { musicPlayerMutable.value?.let { audio ->
        val duration = audio.duration.takeIf { it > 0 } ?: return
        audio.seekTo((duration * fraction.coerceIn(0f, 1f)).toLong())
    } }
    fun musicShuffle() { val audio = musicPlayerMutable.value; if (audio != null) audio.shuffleModeEnabled = !audio.shuffleModeEnabled else musicMutable.update { it.copy(queue = it.queue.copy(shuffle = !it.queue.shuffle)) } }
    fun musicRepeat() { val audio = musicPlayerMutable.value; if (audio != null) audio.repeatMode = (audio.repeatMode + 1) % 3 else musicMutable.update { it.copy(queue = it.queue.copy(repeat = MusicRepeat.entries[(it.queue.repeat.ordinal + 1) % 3])) } }
    fun musicSpeed(value: Float) { if (value in listOf(.75f, 1f, 1.25f, 1.5f, 2f)) { musicPlayerMutable.value?.setPlaybackSpeed(value); musicMutable.update { it.copy(queue = it.queue.copy(speed = value)) } } }
    fun musicMove(from: Int, to: Int) {
        val old = musicMutable.value
        if (from !in old.tracks.indices || to !in old.tracks.indices) return
        val tracks = old.tracks.toMutableList().apply { add(to, removeAt(from)) }
        musicMutable.value = old.copy(tracks = tracks, queue = old.queue.move(from, to))
        musicPlayerMutable.value?.moveMediaItem(from, to)
    }
    fun stopMusic() {
        if (musicActive) releaseCurrent()
        musicMutable.value = MusicPlaybackState(); musicSources.clear(); sourceTracks.clear()
    }
    fun releasePausedMusic() { if (musicActive && musicState.value.phase !in listOf(AudioPhase.Playing, AudioPhase.Loading)) {
        val interrupted = musicMutable.value.interrupted; releaseCurrent(); musicMutable.update { it.copy(interrupted = interrupted) }
    } }
}
