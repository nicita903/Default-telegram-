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
import com.vector.messenger.design.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment

@Composable
fun MusicScreen(onSection: (String) -> Unit = {}) {
    var section by rememberSaveable { mutableStateOf("Tracks") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Your offline library", style = MaterialTheme.typography.headlineSmall)
        Text("Keep the music you choose, ready wherever you are.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf("Tracks", "Albums", "Artists", "Playlists", "Favorites", "Recently played").forEach { title ->
            Row(Modifier.fillMaxWidth().excludeModuleGestures().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { section = title; onSection(title) }
                .heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(36.dp).background(DefaultDesign.colors.panel, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(VectorIcons.Music, null, Modifier.size(18.dp), tint = DefaultDesign.colors.accent)
                }
                Text(title, color = if (section == title) DefaultDesign.colors.accent else DefaultDesign.colors.text)
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
