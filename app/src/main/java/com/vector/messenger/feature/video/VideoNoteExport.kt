package com.vector.messenger.feature.video

import android.content.Context
import android.graphics.*
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.media3.common.*
import androidx.media3.effect.*
import androidx.media3.transformer.*
import com.google.common.collect.ImmutableList
import com.vector.core.media.VideoNoteClip
import java.io.File
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun exportVideoNote(context: Context, source: File, output: File): VideoNoteClip {
    // A square crop AND an encoded circular mask: no scene pixels exist outside the circle.
    val mask = withContext(Dispatchers.Default) { Bitmap.createBitmap(480, 480, Bitmap.Config.ARGB_8888).apply {
        val canvas = Canvas(this); canvas.drawColor(Color.BLACK)
        canvas.drawCircle(240f, 240f, 240f, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    } }
    try {
        withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, result: ExportResult) { if (continuation.isActive) continuation.resume(Unit) }
                        override fun onError(composition: Composition, result: ExportResult, error: ExportException) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Video encoding failed")) }
                    }).build()
                val item = EditedMediaItem.Builder(MediaItem.Builder().setUri(source.toURI().toString())
                    .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setEndPositionMs(59_900).build()).build())
                    .setEffects(Effects(emptyList(), listOf(Presentation.createForWidthAndHeight(480, 480, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP),
                        OverlayEffect(ImmutableList.of<TextureOverlay>(BitmapOverlay.createStaticBitmapOverlay(mask)))))).build()
                continuation.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { transformer.cancel() } }
                try { transformer.start(item, output.absolutePath) }
                catch (_: Exception) { if (continuation.isActive) continuation.resumeWithException(IllegalStateException("Video encoding unavailable")) }
            }
        }
        return validateVideoNote(output)
    } finally { /* Overlay can still be referenced by a cancelling GPU export; GC owns the bitmap. */ }
}
internal suspend fun validateVideoNote(file: File): VideoNoteClip = withContext(Dispatchers.IO) {
    require(file.isFile && file.length() > 0)
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.path)
        val tracks = (0 until extractor.trackCount).map(extractor::getTrackFormat)
        val video = tracks.first { it.getString(MediaFormat.KEY_MIME) == "video/avc" }
        require(video.getInteger(MediaFormat.KEY_WIDTH) == video.getInteger(MediaFormat.KEY_HEIGHT) && video.getInteger(MediaFormat.KEY_WIDTH) in 1..640)
        require(tracks.any { it.getString(MediaFormat.KEY_MIME) == "audio/mp4a-latm" })
        val reader = MediaMetadataRetriever()
        try {
            reader.setDataSource(file.path)
            val millis = reader.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
            val seconds = ((millis + 999) / 1000).toInt()
            require(seconds in 1..60)
            val thumbnail = File(file.parentFile, file.nameWithoutExtension + ".jpg")
            reader.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 160, 160)?.let { bitmap ->
                try { thumbnail.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 82, it) } } finally { bitmap.recycle() }
            }
            VideoNoteClip(file.path, seconds, video.getInteger(MediaFormat.KEY_WIDTH), thumbnail.path.takeIf { thumbnail.isFile })
        } finally { reader.release() }
    } finally { extractor.release() }
}
