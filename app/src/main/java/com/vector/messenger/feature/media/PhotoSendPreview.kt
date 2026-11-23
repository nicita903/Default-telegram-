package com.vector.messenger.feature.media

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.vector.core.media.PhotoQuality
import com.vector.messenger.design.*
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.LocalAppearance

@Composable
fun PhotoSendPreview(
    uris: List<String>, caption: String, album: Boolean, busy: Boolean, uncertain: Boolean, error: String?,
    viewModel: ChatsViewModel, onCaption: (String) -> Unit, onAlbum: (Boolean) -> Unit,
    onRemove: (String) -> Unit, onSend: () -> Unit, onDismiss: () -> Unit,
    initialUri: String? = null, quality: PhotoQuality = PhotoQuality.STANDARD, onQuality: (PhotoQuality) -> Unit = {},
) {
    if (uris.isEmpty()) return
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    var current by rememberSaveable(initialUri) { mutableStateOf(initialUri ?: uris.first()) }
    val index = uris.indexOf(current).coerceAtLeast(0)
    val uri = uris[index]
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().designCanvas().safeDrawingPadding().imePadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1} / ${uris.size} · Photos", Modifier.weight(1f), color = colors.text, style = MaterialTheme.typography.titleMedium)
                DefaultIconControl(Icons.Default.Close, "Return to gallery", onDismiss, enabled = !busy, quiet = true)
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                key(uri) {
                    var loaded by remember { mutableStateOf(false) }
                    val bitmap by produceState<Bitmap?>(null, uri) { value = viewModel.photoPreview(uri, 2048); loaded = true }
                    val image = remember(bitmap) { bitmap?.let { DecodedMediaImage(it, it.width, it.height) } }
                    ZoomableImage(image, image?.width ?: 0, image?.height ?: 0, null, uri, "Selected photo ${index + 1}",
                        if (loaded) "Preview unavailable. Remove this photo or choose it again." else "Loading preview…",
                        onTap = {}, onClose = { if (!busy) onDismiss() }, onNavigate = { delta ->
                            if (!busy) uris.getOrNull(index + delta)?.let { current = it }
                        })
                }
            }
            if (uris.size > 1) LazyRow(Modifier.fillMaxWidth().height(64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(uris, key = { it }) { photo ->
                    val thumbnail by produceState<Bitmap?>(null, photo) { value = viewModel.photoPreview(photo, 128) }
                    Box(Modifier.size(56.dp).clip(DefaultDesign.selectionShape)
                        .border(1.dp, if (photo == uri) colors.accent else Color.Transparent, DefaultDesign.selectionShape)
                        .clickable(enabled = !busy, role = Role.Button) { current = photo }
                        .semantics { contentDescription = "Preview selected photo ${uris.indexOf(photo) + 1}"; selected = photo == uri },
                        contentAlignment = Alignment.Center) {
                        thumbnail?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    }
                }
            }
            DefaultAction("Remove photo", { onRemove(uri) }, enabled = !busy, compact = true)
            BasicTextField(caption, onCaption, readOnly = busy, maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text), cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp).semantics { contentDescription = "Media caption" },
                decorationBox = { editor -> Box { if (caption.isEmpty()) Text("Add a caption…", color = colors.secondary); editor() } })
            if (caption.length > 1024) Text("Caption limit: 1,024 characters.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
            if (uris.size > 1 && quality != PhotoQuality.FILE) DefaultAction(if (album) "Albums of up to 10" else "Send separately", { onAlbum(!album) }, enabled = !busy && !uncertain, compact = true)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)) }
            Row(Modifier.fillMaxWidth().height(40.dp).clip(DefaultDesign.selectionShape).background(colors.panel)) {
                PhotoQuality.entries.forEach { mode ->
                    val active = quality == mode
                    val fill by animateColorAsState(if (active) colors.accent.copy(alpha = .14f) else Color.Transparent,
                        VectorMotionSpec.feedback(appearance), label = "Media quality")
                    Box(Modifier.weight(1f).fillMaxHeight().background(fill)
                        .clickable(enabled = !busy && !uncertain, role = Role.Tab) { onQuality(mode) }
                        .semantics { selected = active }, contentAlignment = Alignment.Center) {
                        Text(mode.name, color = if (active) colors.accent else colors.secondary, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            Text(when (quality) {
                PhotoQuality.STANDARD -> "Up to 1280 px · compressed JPEG"
                PhotoQuality.HD -> "Up to 2560 px · higher-quality JPEG · Telegram may resize"
                PhotoQuality.FILE -> "Original bytes and metadata · sent as separate files"
            }, color = colors.secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 4.dp))
            DefaultAction(if (busy) "Preparing / submitting media…" else "Send ${uris.size}", onSend,
                Modifier.fillMaxWidth(), emphasized = true, enabled = !busy && !uncertain && caption.length <= 1024)
        }
    }
}
