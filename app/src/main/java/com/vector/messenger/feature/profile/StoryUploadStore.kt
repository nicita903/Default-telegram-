package com.vector.messenger.feature.profile

import android.content.Context
import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import com.vector.messenger.telegram.StoryFailure
import com.vector.messenger.telegram.request

internal data class PreparedStory(val path: String, val content: JSONObject)
internal class StoryUploadStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val decoder = Semaphore(1)
    suspend fun prepare(value: String): PreparedStory = decoder.withPermit { withContext(Dispatchers.IO) {
        val uri = Uri.parse(value)
        if (uri.scheme != "content") throw StoryFailure("Select media from your phone.")
        val mime = context.contentResolver.getType(uri).orEmpty()
        val directory = File(context.noBackupFilesDir, "telegram/files/outgoing_stories").apply { check(mkdirs() || isDirectory) }
        val file = File(directory, "${UUID.randomUUID()}.${if (mime.startsWith("video/")) "mp4" else "jpg"}")
        try {
            val local = request("inputFileLocal", "path" to file.path)
            if (mime.startsWith("image/")) {
                var decoded: Bitmap? = null; var target: Bitmap? = null
                try {
                    val image = ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { settings, info, _ ->
                        val scale = minOf(1.0, 1920.0 / maxOf(info.size.width, info.size.height))
                        settings.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                        settings.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                    }
                    decoded = image
                    val canvas = Bitmap.createBitmap(1080, 1920, Bitmap.Config.ARGB_8888); target = canvas
                    val scale = minOf(1080f / image.width, 1920f / image.height)
                    Canvas(canvas).apply {
                        drawColor(Color.BLACK)
                        drawBitmap(image, null, RectF((1080 - image.width * scale) / 2, (1920 - image.height * scale) / 2,
                            (1080 + image.width * scale) / 2, (1920 + image.height * scale) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                    }
                    file.outputStream().use { check(canvas.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
                } finally { decoded?.recycle(); target?.recycle() }
                ensureActive()
                if (file.length() !in 1..10_000_000) throw StoryFailure("Telegram Story photos must be smaller than 10 MB.")
                PreparedStory(file.path, request("inputStoryContentPhoto", "photo" to local, "added_sticker_file_ids" to JSONArray()))
            } else if (mime == "video/mp4") {
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        ensureActive(); val count = input.read(buffer); if (count < 0) break
                        total += count; if (total > 500_000_000) throw StoryFailure("Choose a video below 500 MB.")
                        output.write(buffer, 0, count)
                    }
                } } ?: throw StoryFailure("This video is unavailable.")
                val retriever = MediaMetadataRetriever()
                val duration: Double
                val silent: Boolean
                try {
                    retriever.setDataSource(file.path)
                    duration = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toDoubleOrNull() ?: 0.0) / 1000.0
                    silent = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes"
                    if (duration <= 0 || duration > 60) throw StoryFailure("Telegram Story videos must be 60 seconds or shorter.")
                    if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.let { it != 0 } == true)
                        throw StoryFailure("Story video must be encoded upright at 720×1280; automatic video conversion is not available yet.")
                } finally { retriever.release() }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(file.path)
                    val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                        ?: throw StoryFailure("This MP4 has no video track.")
                    val format = extractor.getTrackFormat(track)
                    if (format.getString(MediaFormat.KEY_MIME) != "video/hevc" || format.getInteger(MediaFormat.KEY_WIDTH) != 720 || format.getInteger(MediaFormat.KEY_HEIGHT) != 1280)
                        throw StoryFailure("This TDLib requires 720×1280 H.265 MP4 Story video. Automatic transcoding is not available yet.")
                    extractor.selectTrack(track)
                    var previousKey = -1L
                    while (extractor.sampleTime >= 0) {
                        ensureActive()
                        if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                            val now = extractor.sampleTime
                            if ((previousKey < 0 && now > 100_000) || previousKey >= 0 && now - previousKey > 1_200_000)
                                throw StoryFailure("Story video needs a key frame each second. Export a compatible video first.")
                            previousKey = now
                        }
                        if (!extractor.advance()) break
                    }
                    if (previousKey < 0 || duration * 1_000_000 - previousKey > 1_200_000)
                        throw StoryFailure("Story video needs a key frame each second.")
                } finally { extractor.release() }
                RandomAccessFile(file, "r").use { input ->
                    var moov = false
                    while (input.filePointer + 8 <= input.length()) {
                        ensureActive()
                        val start = input.filePointer
                        var size = input.readInt().toLong() and 0xffffffffL
                        val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                        if (size == 1L) size = input.readLong()
                        if (type == "moov") moov = true
                        if (type == "mdat" && !moov) throw StoryFailure("Story video must be streamable (fast-start MP4). Export a compatible video first.")
                        if (size <= 0 || start + size > input.length() || start + size <= input.filePointer) break
                        input.seek(start + size)
                    }
                    if (!moov) throw StoryFailure("This MP4 is not a supported Story video.")
                }
                PreparedStory(file.path, request("inputStoryContentVideo", "video" to local, "added_sticker_file_ids" to JSONArray(),
                    "duration" to duration, "cover_frame_timestamp" to 0.0, "is_animation" to silent))
            } else throw StoryFailure("Choose a photo or MPEG4 video.")
        } catch (failure: Throwable) { file.delete(); throw failure }
    } }
    suspend fun discard(prepared: PreparedStory) = withContext(Dispatchers.IO) {
        val file = File(prepared.path).canonicalFile
        val root = File(context.noBackupFilesDir, "telegram/files/outgoing_stories").canonicalFile.toPath()
        if (file.toPath().startsWith(root)) file.delete()
        Unit
    }
}
