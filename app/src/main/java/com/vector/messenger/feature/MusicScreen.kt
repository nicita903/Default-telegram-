package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vector.messenger.navigation.gesture.excludeModuleGestures

@Composable
fun MusicScreen(onSection: (String) -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf("Tracks") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your offline library", style = MaterialTheme.typography.headlineSmall)
        Text("Keep the music you choose, ready wherever you are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Tracks", "Albums", "Artists", "Playlists", "Favorites", "Recently played").chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.excludeModuleGestures()) {
                row.forEach { title ->
                    FilterChip(selected = section == title, onClick = { section = title; onSection(title) }, label = { Text(title) })
                }
            }
        }
        HorizontalDivider()
        Text("Imported music will use durable storage, separate from Telegram's temporary cache. Protected content must remain protected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MusicSectionScreen(section: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EmptyState(section, "Your offline library")
        EmptyState("No ${section.lowercase()} yet", "Audio importing and Media3 playback arrive in Phase 5. Your place in this section is preserved when switching modules.")
    }
}

@Composable
fun DownloadsScreen() {
    Column(Modifier.fillMaxSize()) {
        EmptyState("No downloads", "Telegram downloads require the messaging integration in Phase 3. Offline music importing follows in Phase 5.")
    }
}
