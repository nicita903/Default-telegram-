package com.vector.messenger.feature.media

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.EmptyState
import com.vector.messenger.navigation.gesture.excludeModuleGestures

@Composable
fun DownloadManagerScreen() {
    val viewModel = LocalMediaViewModel.current
    if (viewModel == null) { EmptyState("Downloads", "Sign in to manage Telegram downloads."); return }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val actionError by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = DefaultDesign.colors
    var selected by remember(state.session) { mutableStateOf<MediaItem?>(null) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Telegram downloads", color = colors.text, style = MaterialTheme.typography.titleMedium) }
        item { Text("Remove hides the entry. Cached files are retained and managed by Telegram.", color = colors.secondary, style = MaterialTheme.typography.bodySmall) }
        (actionError ?: state.error)?.let { item { Text(it, color = MaterialTheme.colorScheme.error); DefaultAction("Retry list", viewModel::loadMore) } }
        if (!state.authenticated) item { EmptyState("No downloads", "Sign in to manage Telegram downloads.") }
        else if (state.downloads.isEmpty() && !state.loading) item { EmptyState("No downloads", "Download a photo, video, animation or document from a conversation.") }
        DownloadPhase.entries.forEach { phase ->
            val entries = state.downloads.filter { it.phase == phase }
            if (entries.isNotEmpty()) item(key = "section:$phase") { Text(phase.name, color = colors.secondary, style = MaterialTheme.typography.labelSmall) }
            items(entries, key = { "file:${it.file.id}" }, contentType = { "download" }) { entry ->
                Column(Modifier.fillMaxWidth().excludeModuleGestures()) {
                    Text(entry.item.media.name, color = colors.text, maxLines = 2)
                    Text("${entry.item.media.kind} · ${entry.item.media.mime} · ${fileSize(entry.file.downloaded)} / ${fileSize(entry.file.size)}", color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                    if (entry.phase in listOf(DownloadPhase.Downloading, DownloadPhase.Queued))
                        LinearProgressIndicator(progress = { entry.file.progress }, Modifier.fillMaxWidth().padding(vertical = 6.dp), color = colors.accent)
                    entry.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (entry.phase) {
                            DownloadPhase.Downloading, DownloadPhase.Queued -> DefaultAction("Pause", { viewModel.pause(entry.file.id) }, compact = true)
                            DownloadPhase.Complete -> {
                                DefaultAction("View", { selected = entry.item }, compact = true)
                                DefaultAction("Open", { viewModel.external(context, entry.item, false) }, compact = true)
                                DefaultAction("Share", { viewModel.external(context, entry.item, true) }, compact = true)
                            }
                            else -> DefaultAction(if (entry.phase == DownloadPhase.Paused) "Resume" else "Retry", { viewModel.download(entry.item) }, compact = true)
                        }
                        DefaultAction("Remove", { viewModel.remove(entry.file.id) }, compact = true)
                    }
                    DefaultRule(Modifier.padding(top = 10.dp))
                }
            }
        }
        if (state.loading) item { Text("Loading…", color = colors.secondary) }
        if (state.more) item { DefaultAction("Load more", viewModel::loadMore, enabled = !state.loading) }
    }
    selected?.let { item -> MediaViewer(listOf(item), item.messageId, viewModel) { selected = null } }
}
