package com.vector.messenger.feature.music

import android.content.Context
import android.graphics.*
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import com.vector.messenger.design.DesignIcon as Icon
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.*
import com.vector.core.music.MusicTrack
import com.vector.messenger.design.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*

internal object MusicArtwork {
    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) { override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount }
    private val gate = Semaphore(2)
    suspend fun load(context: Context, track: MusicTrack): Bitmap? = withContext(Dispatchers.IO) { gate.withPermit {
        cache.get(track.key)?.let { return@withPermit it }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, Uri.parse(track.uri))
            val bytes = retriever.embeddedPicture ?: return@withPermit null
            if (bytes.size > 8 * 1024 * 1024) return@withPermit null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 512) sample *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { cache.put(track.key, it) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } catch (_: OutOfMemoryError) { cache.evictAll(); null }
        finally { retriever.release() }
    } }
}

@Composable
fun AlbumArtwork(track: MusicTrack?, size: Dp) {
    val context = LocalContext.current
    val image by produceState<Bitmap?>(null, track?.key, track?.uri) { value = track?.let { MusicArtwork.load(context.applicationContext, it) } }
    Box(Modifier.size(size).clip(DefaultDesign.avatarShape).background(DefaultDesign.colors.raised), contentAlignment = Alignment.Center) {
        val bitmap = image
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Album artwork", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(VectorIcons.Music, "Album artwork unavailable", Modifier.size(size * .38f), tint = DefaultDesign.colors.accent)
    }
}
