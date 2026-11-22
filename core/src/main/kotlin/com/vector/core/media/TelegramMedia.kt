package com.vector.core.media

import kotlinx.coroutines.flow.Flow

enum class MediaKind { Photo, Video, Animation, Document, Voice, Audio }
enum class DownloadPhase { Available, Queued, Downloading, Complete, Paused, Failed }

data class MediaFile(
    val id: Int, val size: Long = 0, val downloaded: Long = 0,
    val active: Boolean = false, val complete: Boolean = false, val path: String? = null,
    val canDownload: Boolean = true,
    val uploaded: Long = 0, val uploading: Boolean = false,
) {
    val progress: Float get() = if (size > 0) (downloaded.toDouble() / size).coerceIn(0.0, 1.0).toFloat() else 0f
    override fun toString() = "MediaFile([redacted])"
}

data class TelegramMedia(
    val kind: MediaKind, val file: MediaFile, val name: String, val mime: String,
    val thumbnail: MediaFile? = null, val width: Int = 0, val height: Int = 0, val duration: Int = 0,
    val waveform: List<Int> = emptyList(),
    val artist: String = "", val album: String = "",
) { override fun toString() = "TelegramMedia([redacted])" }

data class MediaItem(val chatId: Long, val messageId: Long, val media: TelegramMedia, val exportAllowed: Boolean) {
    override fun toString() = "MediaItem([redacted])"
}
data class DownloadEntry(val item: MediaItem, val file: MediaFile, val phase: DownloadPhase, val error: String? = null) {
    override fun toString() = "DownloadEntry([redacted])"
}
data class DownloadPage(val entries: List<DownloadEntry>, val nextOffset: String)
sealed interface MediaEvent {
    data class File(val value: MediaFile) : MediaEvent
    data class Added(val entry: DownloadEntry) : MediaEvent
    data class Paused(val id: Int, val paused: Boolean) : MediaEvent
    data class Removed(val id: Int) : MediaEvent
}

interface TelegramFileClient {
    val updates: Flow<MediaEvent>
    suspend fun downloads(offset: String): DownloadPage
    suspend fun start(item: MediaItem, managed: Boolean, priority: Int): MediaFile
    suspend fun pause(id: Int, managed: Boolean)
    suspend fun remove(id: Int)
    suspend fun validate(file: MediaFile): MediaFile
    suspend fun exportFile(item: MediaItem): MediaFile?
}

/** Viewer paging never fetches or retains an entire media history. */
fun adjacentMedia(items: List<MediaItem>, currentId: Long, delta: Int): MediaItem? {
    val index = items.indexOfFirst { it.messageId == currentId }
    return if (index < 0) null else items.getOrNull(index + delta)
}

enum class ViewerAction { None, Previous, Next, Dismiss }
fun viewerRelease(dx: Float, dy: Float, zoom: Float, multiTouch: Boolean, cancelled: Boolean, density: Float): ViewerAction {
    if (cancelled || multiTouch || zoom > 1.01f) return ViewerAction.None
    if (dy > 80 * density && dy > kotlin.math.abs(dx) * 1.5f) return ViewerAction.Dismiss
    if (kotlin.math.abs(dx) > 64 * density && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f)
        return if (dx < 0) ViewerAction.Next else ViewerAction.Previous
    return ViewerAction.None
}
