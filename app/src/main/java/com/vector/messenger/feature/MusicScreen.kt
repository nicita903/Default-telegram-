package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MusicScreen() {
    var section by rememberSaveable { mutableStateOf("Tracks") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your offline library", style = MaterialTheme.typography.headlineSmall)
        Text("Keep the music you choose, ready wherever you are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Tracks", "Albums", "Artists", "Playlists", "Favorites", "Recently played").chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { title ->
                    FilterChip(selected = section == title, onClick = { section = title }, label = { Text(title) })
                }
            }
        }
        HorizontalDivider()
        EmptyState("No ${section.lowercase()} yet", "Audio importing and Media3 playback arrive in Phase 5. This foundation has no sample audio or active player.")
        Text("Imported music will use durable storage, separate from Telegram's temporary cache. Protected content must remain protected.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun DownloadsScreen() {
    EmptyState("No downloads", "Telegram downloads require the messaging integration in Phase 3. Offline music importing follows in Phase 5.")
}
