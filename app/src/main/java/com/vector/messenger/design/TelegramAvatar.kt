package com.vector.messenger.design

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import com.vector.core.profile.ProfilePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Small completed TDLib avatars only. No URL fetch or bitmap cache on the UI thread. */
@Composable
fun TelegramAvatar(name: String, photo: ProfilePhoto?, size: Dp) {
    key(photo?.fileId, photo?.localPath) {
        val path = photo?.localPath
        val bitmap by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                if (path == null) null else try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        var sample = 1
                        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }
                } catch (_: Exception) { null }
            }
        }
        if (bitmap == null) ChatAvatar(name, size)
        else Image(bitmap!!, "Conversation picture", Modifier.size(size).clip(CircleShape), contentScale = ContentScale.Crop)
    }
}
