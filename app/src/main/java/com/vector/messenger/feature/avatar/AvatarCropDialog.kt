package com.vector.messenger.feature.avatar

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.media.AvatarCropArea
import kotlin.math.*

@Composable
internal fun AvatarCropDialog(uri: String, chats: ChatsViewModel, avatars: AvatarViewModel, edit: AvatarEditState, onDismiss: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, uri) { value = chats.photoPreview(uri, 2048) }
    var viewport by remember(uri) { mutableIntStateOf(0) }
    var zoom by remember(uri) { mutableFloatStateOf(1f) }
    var pan by remember(uri) { mutableStateOf(Offset.Zero) }
    Dialog(onDismissRequest = { if (!edit.busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().designCanvas().safeDrawingPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Profile photo", color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.weight(1f))
            val image = bitmap
            if (image == null) Text("Loading photo…", color = DefaultDesign.colors.secondary)
            else {
                val display = remember(image) { image.asImageBitmap() }
                Canvas(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).onSizeChanged { viewport = it.width }
                    .pointerInput(uri, viewport, image, edit.busy) {
                        if (edit.busy || viewport <= 0) return@pointerInput
                        detectTransformGestures { centroid, move, scale, _ ->
                            val next = (zoom * scale).coerceIn(1f, 5f)
                            val center = Offset(viewport / 2f, viewport / 2f)
                            val moved = (pan - (centroid - center)) * (next / zoom) + (centroid - center) + move
                            val fit = max(viewport.toFloat() / image.width, viewport.toFloat() / image.height)
                            val x = max(0f, (image.width * fit * next - viewport) / 2)
                            val y = max(0f, (image.height * fit * next - viewport) / 2)
                            pan = Offset(moved.x.coerceIn(-x, x), moved.y.coerceIn(-y, y)); zoom = next
                        }
                    }) {
                    val fit = max(size.width / image.width, size.height / image.height) * zoom
                    drawImage(display, dstOffset = IntOffset(((size.width - image.width * fit) / 2 + pan.x).roundToInt(),
                        ((size.height - image.height * fit) / 2 + pan.y).roundToInt()),
                        dstSize = IntSize((image.width * fit).roundToInt(), (image.height * fit).roundToInt()))
                }
            }
            Text("Drag to reposition · Pinch to zoom", Modifier.padding(vertical = 12.dp), color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.weight(1f))
            if (edit.busy) {
                if (edit.progress != null) LinearProgressIndicator(progress = { edit.progress }, modifier = Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
                Text("Updating Telegram profile…", color = DefaultDesign.colors.secondary)
            }
            edit.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (edit.uncertain) DefaultAction("Refresh profile", avatars::refresh)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultAction("Cancel", onDismiss, Modifier.weight(1f), enabled = !edit.busy)
                DefaultAction("Save", {
                    if (image != null && viewport > 0) {
                        val fit = max(viewport.toFloat() / image.width, viewport.toFloat() / image.height) * zoom
                        val left = (viewport - image.width * fit) / 2 + pan.x
                        val top = (viewport - image.height * fit) / 2 + pan.y
                        avatars.change(uri, AvatarCropArea(-left / fit / image.width, -top / fit / image.height,
                            viewport / fit / image.width, viewport / fit / image.height))
                    }
                }, Modifier.weight(1f), emphasized = true, enabled = image != null && viewport > 0 && !edit.busy && !edit.uncertain)
            }
        }
    }
}
