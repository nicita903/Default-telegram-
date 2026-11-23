package com.vector.messenger.feature.music

import androidx.activity.compose.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.*
import com.vector.core.music.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.EmptyState
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.search.MusicLibraryRepository
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.flow.*

@Composable
fun MusicHome(onSection: (String) -> Unit) {
    val vm = LocalMusicViewModel.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Your offline library", color = DefaultDesign.colors.text, style = MaterialTheme.typography.headlineSmall)
        if (vm != null) MusicContinue(vm)
        Text("Library", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        listOf("Tracks", "Albums", "Artists", "Playlists", "Folders", "Recently added", "Recently played").forEach { section ->
            Row(Modifier.fillMaxWidth().excludeModuleGestures().clickable { onSection(section) }.heightIn(min = if (LocalAppearance.current.compact) 52.dp else 64.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AlbumArtwork(null, 36.dp)
                Text(if (section == "Tracks") "All Songs" else section, color = DefaultDesign.colors.text)
            }
        }
        if (vm != null) {
            val library by vm.library.collectAsStateWithLifecycle()
            if (library.recent.isNotEmpty()) {
                Text("Recently played", color = DefaultDesign.colors.secondary)
                val recent = library.recent.take(4).mapNotNull { key -> library.songs.find { it.key == key } }
                recent.forEach { track -> MusicSongRow(track, { vm.play(recent, track.key) }) {} }
            }
        }
    }
}

@Composable
private fun MusicContinue(vm: MusicViewModel) {
    val playback by vm.playback.collectAsStateWithLifecycle()
    val saved by vm.repository.settings.collectAsStateWithLifecycle()
    val restored by vm.restoredQueueTracks.collectAsStateWithLifecycle()
    val track = playback.current ?: restored.find { it.key == saved.queue.keys.getOrNull(saved.queue.index) } ?: restored.firstOrNull()
    if (track != null) Row(Modifier.fillMaxWidth().excludeModuleGestures(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AlbumArtwork(track, 48.dp)
        Column(Modifier.weight(1f).clickable { vm.openPlayer() }) {
            Text("Continue listening", color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelSmall)
            Text(track.title, color = DefaultDesign.colors.text, maxLines = 1)
        }
        DefaultAction(if (playback.phase == com.vector.messenger.feature.audio.AudioPhase.Playing) "Pause" else "Play", vm.player::toggle, compact = true)
    }
}

@Composable
fun MusicLibrarySection(section: String) {
    val vm = LocalMusicViewModel.current
    if (vm == null) { EmptyState(section, "Music library unavailable in this preview."); return }
    val library by vm.library.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var collection by rememberSaveable(section) { mutableStateOf<String?>(null) }
    var query by rememberSaveable(section) { mutableStateOf("") }
    var sort by rememberSaveable(section) { mutableStateOf("Title") }
    var selected by remember { mutableStateOf<MusicTrack?>(null) }
    var create by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var playlistName by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    val filter = remember { MutableStateFlow(MusicFilter(section)) }
    LaunchedEffect(section, collection, query, sort) { filter.value = MusicFilter(section, collection, query, sort) }
    val songs by remember(vm, filter) { vm.results(filter) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val groups by remember(vm, filter) { vm.groups(filter) }.collectAsStateWithLifecycle(initialValue = emptyList())
    val playlist = library.playlists.find { it.id == collection }
    BackHandler(enabled = collection != null) { collection = null }
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp).testTag("music_section:$section")) {
        Text(collection?.let { playlist?.name ?: it } ?: if (section == "Tracks") "All Songs" else section, color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(vertical = 12.dp))
        DefaultInput(query, { query = it }, "Filter music", Modifier.fillMaxWidth().excludeModuleGestures())
        FlowRow(Modifier.excludeModuleGestures(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (collection != null) DefaultAction("Library", { collection = null }, compact = true)
            DefaultAction("Sort: $sort", { sort = listOf("Title", "Artist", "Newest", "Duration", "Track").let { it[(it.indexOf(sort) + 1) % it.size] } }, compact = true)
            DefaultAction("Refresh", vm::refresh, compact = true)
            if (section == "Playlists" && collection == null) DefaultAction("Create playlist", { playlistName = ""; create = true }, compact = true)
            if (playlist != null && !playlist.readOnly) {
                DefaultAction("Rename", { playlistName = playlist.name; rename = true }, compact = true)
                DefaultAction("Delete", { delete = true }, compact = true)
            }
        }
        if (!library.permission) DefaultAction("Allow audio access", { permission.launch(MusicLibraryRepository.permission) }, Modifier.excludeModuleGestures(), compact = true)
        (error ?: library.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (playlist != null && playlist.keys.size > songs.size && query.isEmpty()) Text("${playlist.keys.size - songs.size} unavailable tracks retained in this playlist", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        val groupMode = section in listOf("Albums", "Artists", "Playlists", "Folders") && collection == null
        if (!groupMode && songs.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.excludeModuleGestures()) {
            DefaultAction("Play all", { vm.play(songs, songs.first().key) }, compact = true)
            DefaultAction("Shuffle all", { vm.play(songs, songs.first().key, true) }, compact = true)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("music_library"), state = rememberLazyListState(), contentPadding = PaddingValues(vertical = 12.dp)) {
            if (groupMode) {
                items(groups, key = { "collection:${it.first}" }, contentType = { "music_collection" }) { (id, name) ->
                    Row(Modifier.fillMaxWidth().excludeModuleGestures().clickable { collection = id }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        AlbumArtwork(null, 40.dp); Text(name, Modifier.padding(start = 12.dp), color = DefaultDesign.colors.text)
                    }
                }
                if (groups.isEmpty()) item { EmptyState(if (section == "Playlists") "No playlists yet" else "No ${section.lowercase()} found", "${if (section == "Playlists") "Create a local playlist or allow access to your device library." else "Allow audio access or refresh your library."}") }
            } else {
                items(songs, key = { it.key }, contentType = { "song" }) { track ->
                    MusicSongRow(track, { vm.play(songs, track.key) }, { selected = track })
                    if (playlist != null && !playlist.readOnly) Row(Modifier.excludeModuleGestures(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultAction("Remove", { vm.repository.remove(playlist.id, track.key) }, compact = true)
                        val index = playlist.keys.indexOf(track.key)
                        DefaultAction("↑", { vm.repository.reorder(playlist.id, index, index - 1) }, enabled = index > 0, compact = true)
                        DefaultAction("↓", { vm.repository.reorder(playlist.id, index, index + 1) }, enabled = index < playlist.keys.lastIndex, compact = true)
                    }
                }
                if (songs.isEmpty()) item { EmptyState("No ${if (section == "Tracks") "songs" else section.lowercase()} yet", "Your local music appears here. Missing files are never fabricated.") }
            }
        }
    }
    if (create || rename) DefaultDialog(if (rename) "Rename playlist" else "Create playlist", { create = false; rename = false }) {
        DefaultInput(playlistName, { playlistName = it }, "Playlist name", Modifier.fillMaxWidth())
        DefaultAction("Save playlist", { if (rename && playlist != null) vm.repository.rename(playlist.id, playlistName) else vm.repository.playlist(playlistName); create = false; rename = false }, enabled = playlistName.isNotBlank())
    }
    if (delete && playlist != null) DefaultDialog("Delete playlist?", { delete = false }) {
        Text("Songs and Telegram files are retained.", color = DefaultDesign.colors.secondary)
        DefaultAction("Delete playlist", { vm.repository.delete(playlist.id); collection = null; delete = false }, destructive = true)
    }
    selected?.let { track -> DefaultDialog("Add to playlist", { selected = null }) {
        LazyColumn(Modifier.heightIn(max = 320.dp)) { items(library.playlists.filterNot { it.readOnly }, key = { it.id }, contentType = { "playlist" }) { list -> DefaultAction(list.name, { vm.repository.add(list.id, track.key); selected = null }, Modifier.fillMaxWidth()) } }
        if (library.playlists.none { !it.readOnly }) Text("Create a playlist in Library → Playlists first.", color = DefaultDesign.colors.secondary)
        DefaultAction("Done", { selected = null })
    } }
}

@Composable
fun MusicSongRow(track: MusicTrack, play: () -> Unit, more: () -> Unit) {
    val compact = LocalAppearance.current.compact
    Row(Modifier.fillMaxWidth().excludeModuleGestures().padding(vertical = if (compact) 8.dp else 12.dp).heightIn(min = if (compact) 48.dp else 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AlbumArtwork(track, if (compact) 40.dp else 48.dp)
        Column(Modifier.weight(1f).clickable(onClickLabel = "Play ${track.title}", onClick = play)) {
            Text(track.title, color = DefaultDesign.colors.text, maxLines = 1)
            Text("${track.artist.ifBlank { "Artist unavailable" }} · ${track.mime.substringAfter('/').uppercase()}${if (track.number > 0) " · #${track.number}" else ""}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        DefaultAction("+", more, compact = true)
    }
}
