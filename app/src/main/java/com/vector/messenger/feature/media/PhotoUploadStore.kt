package com.vector.messenger.feature.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import com.vector.core.media.UploadPhoto
import com.vector.core.media.PhotoQuality
import android.provider.MediaStore
import android.provider.OpenableColumns
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

data class AvatarCropArea(val left: Float, val top: Float, val width: Float, val height: Float)

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
    suspend fun preview(uri: String, session: Long, edge: Int = 512): Bitmap? = MediaImages.loadUri(context, uri, session, edge)?.bitmap
    suspend fun prepare(uri: String, quality: PhotoQuality = PhotoQuality.STANDARD): UploadPhoto = decoders.withPermit { withContext(Dispatchers.IO) {
        if (quality == PhotoQuality.FILE) return@withContext prepareOriginal(uri)
        var decoded: Bitmap? = null
        var normalized: Bitmap? = null
        var file: File? = null
        try {
            val image = decode(uri, if (quality == PhotoQuality.HD) 2560 else 1280)
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
            var jpegQuality = if (quality == PhotoQuality.HD) 95 else 82
            do {
                output.outputStream().use { check(target.compress(Bitmap.CompressFormat.JPEG, jpegQuality, it)) }
                jpegQuality -= 5
            } while (output.length() > 10_000_000 && jpegQuality >= 72)
            check(output.length() in 1..10_000_000)
            ensureActive()
            UploadPhoto(output.path, width, height)
        } catch (failure: Throwable) {
            file?.delete()
            throw failure
        } finally { normalized?.recycle(); decoded?.recycle() }
    } }
    private suspend fun prepareOriginal(value: String): UploadPhoto {
        val uri = Uri.parse(value)
        require(uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY)
        val original = MediaStore.setRequireOriginal(uri)
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }?.replace(Regex("[\\p{Cntrl}/\\\\]"), "_")?.takeUnless { it.isBlank() || it == "." || it == ".." }
            ?: "Image.${android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(context.contentResolver.getType(uri)) ?: "bin"}"
        val directory = File(context.noBackupFilesDir, "telegram/files/outgoing_photos/${UUID.randomUUID()}").apply { check(mkdirs()) }
        val output = File(directory, name)
        try {
            context.contentResolver.openInputStream(original)?.use { input -> output.outputStream().use { target ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 2_000_000_000L) { "Original file exceeds the supported size" }
                    target.write(buffer, 0, count)
                }
                check(total > 0)
            } } ?: error("Original file unavailable")
            return UploadPhoto(output.path, 0, 0, document = true)
        } catch (failure: Throwable) { output.delete(); directory.delete(); throw failure }
    }
    suspend fun prepareAvatar(uri: String, crop: AvatarCropArea): UploadPhoto = decoders.withPermit { withContext(Dispatchers.IO) {
        var source: Bitmap? = null
        var square: Bitmap? = null
        var scaled: Bitmap? = null
        var output: File? = null
        try {
            require(listOf(crop.left, crop.top, crop.width, crop.height).all { it.isFinite() } && crop.width > 0 && crop.height > 0)
            val image = decode(uri, 2048); source = image
            val side = minOf((crop.width * image.width).roundToInt(), (crop.height * image.height).roundToInt()).coerceIn(1, minOf(image.width, image.height))
            val x = (crop.left * image.width).roundToInt().coerceIn(0, image.width - side)
            val y = (crop.top * image.height).roundToInt().coerceIn(0, image.height - side)
            val cropped = Bitmap.createBitmap(image, x, y, side, side); square = cropped
            val target = Bitmap.createScaledBitmap(cropped, minOf(side, 1024), minOf(side, 1024), true); scaled = target
            val directory = File(context.noBackupFilesDir, "telegram/files/outgoing_photos").apply { check(mkdirs() || isDirectory) }
            val file = File(directory, "avatar-${UUID.randomUUID()}.jpg"); output = file
            file.outputStream().use { check(target.compress(Bitmap.CompressFormat.JPEG, 92, it)) }
            ensureActive()
            UploadPhoto(file.path, target.width, target.height)
        } catch (failure: Throwable) { output?.delete(); throw failure }
        finally {
            val recycled = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Bitmap, Boolean>())
            listOfNotNull(source, square, scaled).forEach { if (recycled.add(it)) it.recycle() }
        }
    } }
    suspend fun discard(photos: List<UploadPhoto>) = withContext(Dispatchers.IO) {
        val root = File(context.noBackupFilesDir, "telegram/files/outgoing_photos").canonicalFile.toPath()
        photos.forEach { photo ->
            val file = File(photo.path).canonicalFile
            if (file.isFile && file.toPath().startsWith(root)) {
                file.delete()
                file.parentFile?.takeIf { it.toPath() != root && it.parentFile?.toPath() == root }?.delete()
            }
        }
    }
    suspend fun release(uris: List<String>) = withContext(Dispatchers.IO) {
        uris.forEach { uri -> runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(uri), android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } }
    }
    suspend fun saveAvatar(path: String, destination: String) = withContext(Dispatchers.IO) {
        val source = File(path).canonicalFile
        val root = File(context.noBackupFilesDir, "telegram/files").canonicalFile.toPath()
        val database = File(context.noBackupFilesDir, "telegram/database")
        val avatarRoots = listOf(root, File(database, "profile_photos").canonicalFile.toPath(),
            File(database, "thumbnails").canonicalFile.toPath())
        require(source.isFile && source.canRead() && source.length() > 0 && avatarRoots.any { source.toPath().startsWith(it) })
        context.contentResolver.openOutputStream(Uri.parse(destination), "w")?.use { output ->
            source.inputStream().use { input ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
        } ?: error("Unable to save photo")
    }
}
