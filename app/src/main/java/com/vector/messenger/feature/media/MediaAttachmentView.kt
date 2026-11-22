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
    val state by remember(viewModel, item.media.file.id, item.media.thumbnail?.id) { viewModel.observe(item) }
        .collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val file = state.file
    val thumb = item.media.thumbnail
    val preview = state.thumbnail ?: file?.takeIf { item.media.kind == MediaKind.Photo }
    LaunchedEffect(state.session, item.media.file.id) {
        if (thumb != null) viewModel.preview(item.copy(media = item.media.copy(file = thumb, thumbnail = null)))
        // Completed originals are validated/reused without triggering full-size auto-downloads.
        if (item.media.file.complete) viewModel.preview(item)
    }
    val image by produceState<DecodedMediaImage?>(null, preview?.path, state.session) {
        value = preview?.path?.let { MediaImages.load(it, 512, state.session) }
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


