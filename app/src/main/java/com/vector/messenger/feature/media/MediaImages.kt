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
    suspend fun load(path: String, edge: Int, session: Long): DecodedMediaImage? = withContext(Dispatchers.IO) {
        decoders.withPermit {
            try {
                val generationAtStart = synchronized(this@MediaImages) { generation }
                val file = File(path)
                if (!file.isFile) return@withPermit null
                val key = "$session:$path:${file.lastModified()}:$edge"
                cache.get(key)?.let { return@withPermit it }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withPermit null
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > edge) sample *= 2
                val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@withPermit null
                ensureActive()
                DecodedMediaImage(bitmap, bounds.outWidth, bounds.outHeight).also { image ->
                    synchronized(this@MediaImages) { if (generationAtStart == generation) cache.put(key, image) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
            catch (_: OutOfMemoryError) { cache.evictAll(); null }
        }
    }
}
