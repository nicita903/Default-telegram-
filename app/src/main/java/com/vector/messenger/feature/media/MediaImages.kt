package com.vector.messenger.feature.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal data class DecodedMediaImage(val bitmap: Bitmap, val width: Int, val height: Int)

/** Bounded sampled previews only; TDLib owns disk storage. No second disk cache. */
internal object MediaImages {
    private val cache = object : LruCache<String, DecodedMediaImage>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: DecodedMediaImage) = value.bitmap.allocationByteCount
    }
    private val decoders = Semaphore(2)
    private var generation = 0L
    @Synchronized fun clear() { generation++; cache.evictAll() }
    suspend fun loadUri(context: android.content.Context, uri: String, session: Long, edge: Int = 512): DecodedMediaImage? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            val key = "picker:$session:$uri:$edge"
            cache.get(key)?.let { return@withPermit it }
            val started = synchronized(this@MediaImages) { generation }
            try {
                var width = 0
                var height = 0
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(context.contentResolver, android.net.Uri.parse(uri))) { decoder, info, _ ->
                    width = info.size.width; height = info.size.height
                    val scale = (edge.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
                ensureActive()
                DecodedMediaImage(bitmap, width, height).also { image -> synchronized(this@MediaImages) {
                    if (started == generation) cache.put(key, image)
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { cache.evictAll(); null }
        }
    }
    suspend fun loadGalleryThumbnail(context: android.content.Context, uri: String, modified: Long, revision: Int): DecodedMediaImage? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            val key = "gallery:$revision:$modified:$uri"
            cache.get(key)?.let { return@withPermit it }
            val started = synchronized(this@MediaImages) { generation }
            suspendCancellableCoroutine<DecodedMediaImage?> { continuation ->
                val signal = android.os.CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    val bitmap = context.contentResolver.loadThumbnail(android.net.Uri.parse(uri), android.util.Size(256, 256), signal)
                    val image = DecodedMediaImage(bitmap, bitmap.width, bitmap.height)
                    if (continuation.isActive) {
                        synchronized(this@MediaImages) { if (started == generation) cache.put(key, image) }
                        continuation.resumeWith(Result.success(image))
                    } else bitmap.recycle()
                } catch (_: Exception) { if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
                catch (_: OutOfMemoryError) { cache.evictAll(); if (continuation.isActive) continuation.resumeWith(Result.success(null)) }
            }
        }
    }
    suspend fun loadVideoUri(context: android.content.Context, uri: String, session: Long, edge: Int = 512): DecodedMediaImage? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            val key = "post-video:$session:$uri:$edge"
            cache.get(key)?.let { return@withPermit it }
            val started = synchronized(this@MediaImages) { generation }
            val retriever = android.media.MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, android.net.Uri.parse(uri))
                val bitmap = retriever.getScaledFrameAtTime(-1, android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC, edge, edge) ?: return@withPermit null
                ensureActive()
                DecodedMediaImage(bitmap, bitmap.width, bitmap.height).also { image -> synchronized(this@MediaImages) {
                    if (started == generation) cache.put(key, image)
                } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { cache.evictAll(); null }
            finally { retriever.release() }
        }
    }
    suspend fun load(path: String, edge: Int, session: Long): DecodedMediaImage? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            try {
                val generationAtStart = synchronized(this@MediaImages) { generation }
                val file = File(path)
                if (!file.isFile) return@withPermit null
                val key = "$session:$path:${file.lastModified()}:$edge"
                cache.get(key)?.let { return@withPermit it }
                var width = 0
                var height = 0
                val bitmap = android.graphics.ImageDecoder.decodeBitmap(android.graphics.ImageDecoder.createSource(file)) { decoder, info, _ ->
                    width = info.size.width; height = info.size.height
                    val scale = (edge.toDouble() / maxOf(width, height)).coerceAtMost(1.0)
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.setTargetSize((width * scale).toInt().coerceAtLeast(1), (height * scale).toInt().coerceAtLeast(1))
                }
                ensureActive()
                DecodedMediaImage(bitmap, width, height).also { image ->
                    synchronized(this@MediaImages) { if (generationAtStart == generation) cache.put(key, image) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { cache.evictAll(); null }
        }
    }
}

private val ImageDocumentExtensions = setOf("jpg", "jpeg", "png", "heic", "heif", "avif", "webp", "gif", "bmp")
internal fun com.vector.core.media.TelegramMedia.isImageDocument(): Boolean =
    kind == com.vector.core.media.MediaKind.Document && (mime.startsWith("image/") ||
        name.substringAfterLast('.', "").lowercase() in ImageDocumentExtensions)
