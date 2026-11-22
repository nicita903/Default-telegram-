package com.vector.core.music

import com.vector.core.media.MediaItem

data class MusicTrack(val id: Long, val title: String, val artist: String, val album: String, val uri: String, val added: Long,
    val duration: Long = 0, val number: Int = 0, val folder: String = "", val artwork: String? = null,
    val mime: String = "", val key: String = "device:$uri", val telegram: MediaItem? = null) {
    override fun toString() = "MusicTrack([redacted])"
}
data class MusicPlaylist(val id: String, val name: String, val keys: List<String>, val readOnly: Boolean = false)
enum class MusicRepeat { Off, One, All }
data class MusicQueue(val keys: List<String> = emptyList(), val index: Int = 0, val position: Long = 0,
    val shuffle: Boolean = false, val repeat: MusicRepeat = MusicRepeat.Off, val speed: Float = 1f) {
    fun normalized(): MusicQueue {
        val unique = keys.distinct().take(1000)
        val current = keys.getOrNull(index)
        return copy(keys = unique, index = unique.indexOf(current).takeIf { it >= 0 } ?: index.coerceIn(0, (unique.size - 1).coerceAtLeast(0)),
            position = position.coerceAtLeast(0), speed = speed.takeIf { it in listOf(.75f, 1f, 1.25f, 1.5f, 2f) } ?: 1f)
    }
    fun move(from: Int, to: Int): MusicQueue {
        if (from !in keys.indices || to !in keys.indices) return this
        val current = keys.getOrNull(index)
        val moved = keys.toMutableList().apply { add(to, removeAt(from)) }
        return copy(keys = moved, index = moved.indexOf(current).coerceAtLeast(0))
    }
}
fun MusicPlaylist.move(from: Int, to: Int): MusicPlaylist = if (readOnly || from !in keys.indices || to !in keys.indices) this
    else copy(keys = keys.toMutableList().apply { add(to, removeAt(from)) })

fun filterMusic(tracks: List<MusicTrack>, query: String): List<MusicTrack> {
    val words = query.trim().lowercase(java.util.Locale.ROOT).split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (words.isEmpty()) return tracks
    return tracks.filter { track -> words.all { word -> track.title.contains(word, true) || track.artist.contains(word, true) || track.album.contains(word, true) } }
}
fun musicMime(mime: String, name: String): String? {
    val extension = name.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
    return when {
        mime in setOf("audio/mpeg", "audio/mp3") || extension == "mp3" -> "audio/mpeg"
        mime in setOf("audio/flac", "audio/x-flac") || extension == "flac" -> "audio/flac"
        mime in setOf("audio/aac", "audio/mp4a-latm") || extension == "aac" -> "audio/mp4a-latm"
        mime in setOf("audio/mp4", "audio/x-m4a") || extension == "m4a" -> "audio/mp4"
        extension == "opus" || mime == "audio/opus" -> "audio/opus"
        mime in setOf("audio/ogg", "application/ogg", "audio/vorbis") || extension == "ogg" -> "audio/ogg"
        mime in setOf("audio/wav", "audio/x-wav", "audio/wave") || extension == "wav" -> "audio/wav"
        else -> null
    }
}
/** Explicit policy: music is parked during voice/video and never automatically resumed. */
interface MusicInterruptionSource { val index: Int; val position: Long; fun pause() }
data class ParkedMusic(val queue: MusicQueue, val requiresManualResume: Boolean = true)
fun parkMusic(queue: MusicQueue, source: MusicInterruptionSource): ParkedMusic {
    val saved = queue.copy(index = source.index, position = source.position).normalized()
    source.pause()
    return ParkedMusic(saved)
}

data class MusicSettings(val playlists: List<MusicPlaylist> = emptyList(), val recent: List<String> = emptyList(),
    val queue: MusicQueue = MusicQueue())

fun musicShuffleOrder(size: Int, first: Int, seed: Long): IntArray {
    if (size <= 0) return intArrayOf()
    val start = first.coerceIn(0, size - 1)
    return (listOf(start) + (0 until size).filter { it != start }.shuffled(kotlin.random.Random(seed))).toIntArray()
}

fun restoreMusicQueue(saved: MusicQueue, available: Set<String>): MusicQueue {
    val original = saved.keys.getOrNull(saved.index)
    val keys = saved.keys.filter { it in available }
    return saved.copy(keys = keys, index = keys.indexOf(original).coerceAtLeast(0),
        position = if (original in keys) saved.position else 0).normalized()
}
