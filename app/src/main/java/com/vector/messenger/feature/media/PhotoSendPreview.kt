package com.vector.messenger.feature.media

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.ChatsViewModel
import kotlinx.coroutines.launch

/** Fullscreen selection workflow, using the active palette and the existing bounded image cache. */
@Composable
fun PhotoSendPreview(
    uris: List<String>, caption: String, album: Boolean, busy: Boolean, uncertain: Boolean, error: String?,
    viewModel: ChatsViewModel, onCaption: (String) -> Unit, onAlbum: (Boolean) -> Unit,
    onRemove: (String) -> Unit, onSend: () -> Unit, onDismiss: () -> Unit,
) {
    if (uris.isEmpty()) return
    val colors = DefaultDesign.colors
    val pager = rememberPagerState(pageCount = { uris.size })
    val scope = rememberCoroutineScope()
    Dialog(onDismissRequest = { if (!busy) onDismiss() }, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Column(Modifier.fillMaxSize().designCanvas().safeDrawingPadding().imePadding().padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${pager.currentPage + 1} / ${uris.size} · Photos", Modifier.weight(1f), color = colors.text,
                    style = MaterialTheme.typography.titleMedium)
                DefaultIconControl(Icons.Default.Close, "Close photo preview", onDismiss, enabled = !busy, quiet = true)
            }
            HorizontalPager(pager, Modifier.fillMaxWidth().weight(1f), key = { uris[it] }, userScrollEnabled = !busy) { page ->
                var loaded by remember(uris[page]) { mutableStateOf(false) }
                val bitmap by produceState<Bitmap?>(null, uris[page]) {
                    value = viewModel.photoPreview(uris[page]); loaded = true
                }
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    bitmap?.let { Image(it.asImageBitmap(), "Selected photo ${page + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        ?: Text(if (loaded) "Preview unavailable. Remove this photo or choose it again." else "Loading preview…", color = colors.secondary)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DefaultAction("Previous", { scope.launch { pager.scrollToPage(pager.currentPage - 1) } }, enabled = !busy && pager.currentPage > 0, compact = true)
                DefaultAction("Remove photo", { uris.getOrNull(pager.currentPage)?.let(onRemove) }, enabled = !busy, compact = true)
                DefaultAction("Next", { scope.launch { pager.scrollToPage(pager.currentPage + 1) } }, enabled = !busy && pager.currentPage < uris.lastIndex, compact = true)
            }
            BasicTextField(caption, onCaption, readOnly = busy, maxLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text), cursorBrush = SolidColor(colors.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 16.dp), decorationBox = { editor ->
                    Box { if (caption.isEmpty()) Text("Add a caption…", color = colors.secondary); editor() }
                })
            if (caption.length > 1024) Text("Caption limit: 1,024 characters.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall)
            if (uris.size > 1) DefaultAction(if (album) "Send as album" else "Send separately", { onAlbum(!album) }, enabled = !busy && !uncertain)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 6.dp)) }
            DefaultAction(if (busy) "Preparing / submitting photos…" else "Send ${uris.size} photo${if (uris.size == 1) "" else "s"}", onSend,
                Modifier.fillMaxWidth(), emphasized = true, enabled = !busy && !uncertain && caption.length <= 1024)
        }
    }
}
