package com.vector.messenger.feature.music

import com.vector.core.music.*
import com.vector.messenger.feature.audio.AudioPhase

data class MusicPlaybackState(val tracks: List<MusicTrack> = emptyList(), val queue: MusicQueue = MusicQueue(),
    val phase: AudioPhase = AudioPhase.Idle, val duration: Long = 0, val interrupted: Boolean = false, val error: String? = null) {
    val current get() = tracks.getOrNull(queue.index)
    override fun toString() = "MusicPlaybackState(phase=$phase)"
}
