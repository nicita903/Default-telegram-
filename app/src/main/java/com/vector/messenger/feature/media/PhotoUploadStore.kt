package com.vector.messenger.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import com.vector.core.media.UploadPhoto
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Bounded software decoding normalizes Android-supported formats/orientation to Telegram JPEG.
 * Accepted sources stay under TDLib's private files root so failed native sends can be retried. */
@Singleton
class PhotoUploadStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val decoders = Semaphore(1)
    private suspend fun decode(uri: String, edge: Int): Bitmap = withContext(Dispatchers.IO) {
        val content = Uri.parse(uri)
        require(content.scheme == "content")
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, content)) { decoder, info, _ ->
            val width = info.size.width
            val height = info.size.height
            require(width > 0 && height > 0)
            val scale = (edge.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize((width * scale).roundToInt().coerceAtLeast(1), (height * scale).roundToInt().coerceAtLeast(1))
        }
    }
    suspend fun preview(uri: String, session: Long): Bitmap? = MediaImages.loadUri(context, uri, session)?.bitmap
    suspend fun prepare(uri: String): UploadPhoto = decoders.withPermit { withContext(Dispatchers.IO) {
        var decoded: Bitmap? = null
        var normalized: Bitmap? = null
        var file: File? = null
        try {
            val image = decode(uri, 2048)
            decoded = image
            ensureActive()
            // Pad very narrow panoramas instead of cropping their contents (Telegram ratio <=20).
            val width = maxOf(image.width, ceil(image.height / 20.0).toInt())
            val height = maxOf(image.height, ceil(image.width / 20.0).toInt())
            val target = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            normalized = target
            Canvas(target).apply {
                drawColor(Color.WHITE)
                drawBitmap(image, (width - image.width) / 2f, (height - image.height) / 2f, null)
            }
            val directory = File(context.noBackupFilesDir, "telegram/files/outgoing_photos").apply { check(mkdirs() || isDirectory) }
            val output = File(directory, "${UUID.randomUUID()}.jpg")
            file = output
            output.outputStream().use { check(target.compress(Bitmap.CompressFormat.JPEG, 88, it)) }
            check(output.length() in 1..10_000_000)
            ensureActive()
            UploadPhoto(output.path, width, height)
        } catch (failure: Throwable) {
            file?.delete()
            throw failure
        } finally { normalized?.recycle(); decoded?.recycle() }
    } }
    suspend fun discard(photos: List<UploadPhoto>) = withContext(Dispatchers.IO) {
        val root = File(context.noBackupFilesDir, "telegram/files/outgoing_photos").canonicalFile.toPath()
        photos.forEach { photo ->
            val file = File(photo.path).canonicalFile
            if (file.isFile && file.toPath().startsWith(root)) file.delete()
        }
    }
    suspend fun release(uris: List<String>) = withContext(Dispatchers.IO) {
        uris.forEach { uri -> runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
    }
}
