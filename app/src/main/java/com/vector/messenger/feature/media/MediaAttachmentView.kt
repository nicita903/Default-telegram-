package com.vector.messenger.feature.media

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import com.vector.core.Delivery
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.*
import com.vector.messenger.design.*

internal fun fileSize(bytes: Long): String = when {
    bytes <= 0 -> "Size unknown"
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> String.format(java.util.Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024))
}

@Composable
fun MediaAttachmentView(item: MediaItem, viewModel: MediaViewModel, onOpen: () -> Unit) {
    MediaAttachmentView(item, viewModel, onOpen, null, {}, {}, false)
}

@Composable
fun MediaAttachmentView(item: MediaItem, viewModel: MediaViewModel, onOpen: () -> Unit,
    delivery: Delivery?, onCancelUpload: () -> Unit, onRetryUpload: () -> Unit, canRetryUpload: Boolean, retryAt: Long = 0) {
    val state by remember(viewModel, item.media.file.id, item.media.thumbnail?.id) { viewModel.observe(item) }
        .collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val file = state.file
    val thumb = item.media.thumbnail
    val imageDocument = item.media.isImageDocument()
    val preview = file?.takeIf { (item.media.kind == MediaKind.Photo || imageDocument) && it.complete && it.path != null }
        ?: state.thumbnail?.takeIf { it.complete && it.path != null }
    LaunchedEffect(state.session, item.media.file.id, thumb?.id, item.media.file.complete) {
        if (thumb != null) viewModel.preview(item.copy(media = item.media.copy(file = thumb, thumbnail = null)))
        // Completed originals are validated/reused without triggering full-size auto-downloads.
        if (item.media.file.complete) viewModel.preview(item)
        else if (delivery !in listOf(Delivery.Sending, Delivery.Failed)) viewModel.automatic(item)
    }
    val image by produceState<DecodedMediaImage?>(null, preview?.path, state.session) {
        value = preview?.path?.let { MediaImages.load(it, 512, state.session) }
    }
    if (item.media.kind == MediaKind.Photo || imageDocument) {
        val transfer = file ?: item.media.file.copy(path = null)
        Column {
        if (imageDocument) {
            Text(item.media.name, maxLines = 1, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
            Text("${fileSize(transfer.size)} · Original file", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        }
        BoxWithConstraints(Modifier.widthIn(max = 260.dp)) {
            val ratio = if (item.media.width > 0 && item.media.height > 0) item.media.width.toFloat() / item.media.height else image?.let { it.width.toFloat() / it.height } ?: 1f
            val width = minOf(maxWidth, 260.dp * ratio).coerceAtLeast(minOf(maxWidth, 80.dp))
            val height = (width / ratio).coerceIn(64.dp, 260.dp)
            Box(Modifier.size(width, height).clip(RoundedCornerShape(12.dp)).background(DefaultDesign.colors.raised)
                .clickable(onClickLabel = "View photo", onClick = onOpen), contentAlignment = Alignment.Center) {
                image?.let { Image(it.bitmap.asImageBitmap(), "Photo", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                if (image == null) Text("Photo · tap to view", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
                if (delivery == Delivery.Sending || delivery == Delivery.Failed) {
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = .72f)).padding(6.dp)) {
                        if (delivery == Delivery.Sending) {
                            val progress = if (transfer.size > 0) (transfer.uploaded.toDouble() / transfer.size).coerceIn(0.0, 1.0).toFloat() else 0f
                            LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
                            Text(if (transfer.uploading) "Uploading ${(progress * 100).toInt()}%" else "Sending…",
                                color = DefaultDesign.colors.text, style = MaterialTheme.typography.labelSmall)
                        } else {
                            val retryReady by produceState(canRetryUpload && retryAt <= System.currentTimeMillis(), item.messageId, canRetryUpload, retryAt) {
                                if (canRetryUpload) {
                                    kotlinx.coroutines.delay((retryAt - System.currentTimeMillis()).coerceAtLeast(0))
                                    value = true
                                } else value = false
                            }
                            Text("Upload failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            DefaultAction(if (retryReady) "Retry upload" else if (canRetryUpload) "Retry later" else "Retry unavailable", onRetryUpload, enabled = retryReady, compact = true)
                        }
                    }
                    DefaultIconControl(Icons.Default.Close, if (delivery == Delivery.Failed) "Remove failed photo" else "Cancel photo upload",
                        onCancelUpload, Modifier.align(Alignment.TopEnd), quiet = true)
                } else if (file?.active == true) {
                    LinearProgressIndicator(progress = { file.progress }, Modifier.align(Alignment.BottomCenter).fillMaxWidth(), color = DefaultDesign.colors.accent)
                }
            }
        }
        }
        return
    }
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "View ${item.media.kind.name.lowercase()}", onClick = onOpen)) {
        if (item.media.kind != MediaKind.Document) {
            Box(Modifier.fillMaxWidth().height(170.dp)
                .background(DefaultDesign.colors.raised), contentAlignment = androidx.compose.ui.Alignment.Center) {
                image?.let { Image(it.bitmap.asImageBitmap(), item.media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                if (image == null || item.media.kind != MediaKind.Photo) Text(when (item.media.kind) {
                    MediaKind.Video -> "▶  Video"
                    MediaKind.Animation -> "▶  Animation"
                    else -> "Photo"
                }, color = DefaultDesign.colors.text)
            }
        }
        Text(item.media.name, maxLines = 2, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
        Text("${fileSize(file?.size ?: item.media.file.size)} · ${item.media.mime}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        if (file?.active == true) {
            LinearProgressIndicator(progress = { file.progress }, Modifier.fillMaxWidth().padding(top = 4.dp), color = DefaultDesign.colors.accent)
            Text("${(file.progress * 100).toInt()}%", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        } else Text(if (file?.complete == true) "View" else "Tap to view / download", color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelSmall)
    }
}


