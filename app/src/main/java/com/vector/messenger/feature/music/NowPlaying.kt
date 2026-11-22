package com.vector.messenger.feature.music

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.lifecycle.compose.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.audio.AudioPhase
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.core.music.MusicTrack
import com.vector.messenger.feature.LocalAppearance
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState

private fun clock(ms: Long) = "%d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

@Composable
fun MusicMiniPlayer() {
    val vm = LocalMusicViewModel.current ?: return
    val state by vm.playback.collectAsStateWithLifecycle()
    var retained by remember { mutableStateOf<MusicTrack?>(null) }
    LaunchedEffect(state.current?.key) { if (state.current != null) retained = state.current }
    val visible = remember { MutableTransitionState(false) }.apply { targetState = state.current != null }
    val settings = LocalAppearance.current
    val track = state.current ?: retained ?: return
    AnimatedVisibility(visible, enter = VectorMotionSpec.menuEnter(settings), exit = VectorMotionSpec.menuExit(settings)) {
    Row(Modifier.fillMaxWidth().excludeModuleGestures().background(DefaultDesign.colors.raised).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        AlbumArtwork(track, 34.dp)
        Column(Modifier.weight(1f).clickable(onClickLabel = "Open Now Playing") { vm.openPlayer() }) {
            Text(track.title, color = DefaultDesign.colors.text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text(track.artist.ifBlank { "Artist unavailable" }, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            LinearProgressIndicator(progress = { if (state.duration > 0) state.queue.position.toFloat() / state.duration else 0f }, Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
        }
        DefaultAction(if (state.phase == AudioPhase.Playing) "Pause" else "Play", vm.player::toggle, compact = true)
        DefaultIconControl(Icons.Default.Close, "Stop music", vm.player::stop, quiet = true)
    }
    }
}

@Composable
fun NowPlayingHost() {
    val vm = LocalMusicViewModel.current ?: return
    val open by vm.nowPlaying.collectAsStateWithLifecycle()
    if (!open) return
    val state by vm.playback.collectAsStateWithLifecycle()
    val saved by vm.repository.settings.collectAsStateWithLifecycle()
    val library by vm.library.collectAsStateWithLifecycle()
    val restoredTracks by vm.restoredQueueTracks.collectAsStateWithLifecycle()
    val currentQueue = if (state.tracks.isEmpty()) com.vector.core.music.restoreMusicQueue(saved.queue, restoredTracks.mapTo(mutableSetOf()) { it.key }) else state.queue
    val displayedTracks = state.tracks.ifEmpty { restoredTracks }
    val actionError by vm.error.collectAsStateWithLifecycle()
    val track = state.current ?: restoredTracks.getOrNull(currentQueue.index)
    var queue by rememberSaveable { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    var seek by remember { mutableFloatStateOf(0f) }
    Dialog(onDismissRequest = vm::closePlayer, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        securePolicy = if (track?.telegram != null) SecureFlagPolicy.SecureOn else SecureFlagPolicy.Inherit)) {
        MusicPalette {
        Column(Modifier.fillMaxSize().designCanvas().safeDrawingPadding().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (queue) "Playback queue" else "Now Playing", Modifier.weight(1f), color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleMedium)
                DefaultIconControl(Icons.Default.Close, "Close player", vm::closePlayer, quiet = true)
            }
            if (queue) {
                DefaultAction("Player", { queue = false }, compact = true)
                LazyColumn(Modifier.weight(1f)) {
                    itemsIndexed(displayedTracks, key = { _, item -> item.key }) { index, item ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { vm.play(displayedTracks, item.key) }) {
                                Text(item.title, color = if (item.key == currentQueue.keys.getOrNull(currentQueue.index)) DefaultDesign.colors.accent else DefaultDesign.colors.text, maxLines = 1)
                                Text(item.artist, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                            }
                            DefaultAction("↑", { vm.player.move(currentQueue.keys.indexOf(item.key), currentQueue.keys.indexOf(displayedTracks[index - 1].key)) }, enabled = index > 0, compact = true)
                            DefaultAction("↓", { vm.player.move(currentQueue.keys.indexOf(item.key), currentQueue.keys.indexOf(displayedTracks[index + 1].key)) }, enabled = index < displayedTracks.lastIndex, compact = true)
                        }
                    }
                }
            } else {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Spacer(Modifier.height(24.dp))
                    AlbumArtwork(track, 220.dp)
                    Text(track?.title ?: "Choose a song from your library", color = DefaultDesign.colors.text, style = MaterialTheme.typography.headlineSmall)
                    Text(track?.artist?.ifBlank { "Artist unavailable" }.orEmpty(), color = DefaultDesign.colors.secondary)
                    track?.album?.takeIf { it.isNotBlank() }?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall) }
                    val duration = state.duration.takeIf { it > 0 } ?: track?.duration ?: 0
                    Slider(value = if (dragging) seek else if (duration > 0) (currentQueue.position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
                        onValueChange = { dragging = true; seek = it }, onValueChangeFinished = { vm.player.seek(seek); dragging = false }, enabled = duration > 0 && vm.player.engine.musicActive)
                        Text("${clock(currentQueue.position)} / ${clock(duration)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        DefaultAction("Previous", { vm.player.next(true) }, compact = true, enabled = vm.player.engine.musicActive)
                        DefaultAction(if (state.phase == AudioPhase.Playing) "Pause" else "Play", vm.player::toggle, emphasized = true, enabled = track != null)
                        DefaultAction("Next", { vm.player.next() }, compact = true, enabled = vm.player.engine.musicActive)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultAction("Shuffle ${if (currentQueue.shuffle) "on" else "off"}", vm.player::shuffle, compact = true)
                        DefaultAction("Repeat ${currentQueue.repeat.name.lowercase()}", vm.player::repeat, compact = true)
                        DefaultAction("${currentQueue.speed}×", { vm.player.speed(listOf(.75f, 1f, 1.25f, 1.5f, 2f).let { it[(it.indexOf(currentQueue.speed) + 1) % it.size] }) }, compact = true)
                        DefaultAction("Queue", { queue = true }, compact = true)
                    }
                    if (state.interrupted) Text("Music paused for another audio activity. Resume when ready.", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
                    (actionError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    track?.let { Text("${it.mime} · ${if (it.telegram == null) "On device" else "Private Telegram download"}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        }
    }
}

