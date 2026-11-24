@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vector.messenger.feature.video

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import androidx.camera.view.PreviewView
import androidx.media3.ui.PlayerView
import com.vector.core.*
import com.vector.core.audio.*
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.media.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Composable
fun VideoNoteButton(chat: Long, reply: Long?, enabled: Boolean) {
    val vm = LocalVideoNotes.current ?: return
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val currentReply by rememberUpdatedState(reply)
    var explanation by remember { mutableStateOf(false) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result -> if (!result.values.all { it }) vm.permissionDenied(chat) }
    fun allowed() = listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
    fun begin(locked: Boolean = false) { if (allowed()) vm.start(chat, currentReply, locked) else explanation = true }
    Box(Modifier.size(48.dp).excludeModuleGestures().background(DefaultDesign.colors.accent, DefaultDesign.controlShape)
        .semantics { contentDescription = "Video note camera"; onClick("Switch to voice messages") { vm.mode(false); true }
            customActions = listOf(CustomAccessibilityAction("Record locked video note") { if (enabled) begin(true); true }) }
        .pointerInput(chat, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(); var tapped = false; var eligible = true
                val early = withTimeoutOrNull(180) {
                    while (true) { val event = awaitPointerEvent(); val change = event.changes.firstOrNull { it.id == down.id }
                        if (event.changes.size != 1 || change == null || change.isConsumed || (change.position - down.position).getDistance() > 12 * density) { eligible = false; break }
                        if (!change.pressed) { tapped = true; break }
                    }; true
                }
                if (early == true) { if (eligible && tapped) vm.mode(false); return@awaitEachGesture }
                if (!eligible) return@awaitEachGesture
                if (!allowed()) { explanation = true; return@awaitEachGesture }
                begin(); var completed = false
                val gesture = VoiceGesture(72 * density, 64 * density)
                try {
                    while (true) { val event = awaitPointerEvent(); if (event.changes.size != 1) break
                        val change = event.changes.single(); if (change.isConsumed) break
                        change.consume(); val delta = change.position - down.position
                        when (gesture.move(delta.x, delta.y)) {
                            VoiceGestureAction.Cancel -> { vm.cancel(); completed = true; break }
                            VoiceGestureAction.Lock -> vm.lock()
                            else -> Unit
                        }
                        if (!change.pressed) { vm.release(); completed = true; break }
                    }
                } finally { if (!completed) vm.cancel() }
            }
        }, contentAlignment = Alignment.Center) { Text("◉", color = DefaultDesign.colors.onAccent, style = MaterialTheme.typography.titleLarge) }
    if (explanation) AlertDialog(onDismissRequest = { explanation = false }, title = { Text("Record a video note") },
        text = { Text("Camera and microphone access are used only while you record. After allowing access, hold the camera button again.") },
        confirmButton = { TextButton({ explanation = false; permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)) }) { Text("Allow access") } },
        dismissButton = { TextButton({ explanation = false }) { Text("Cancel") } })
}

@Composable
fun VideoNoteRecorderPanel(chat: Long) {
    val vm = LocalVideoNotes.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val context = LocalContext.current
    val account by vm.accountId.collectAsStateWithLifecycle()
    LaunchedEffect(chat, vm, account) { vm.restore(chat) }
    DisposableEffect(chat, vm) { onDispose { vm.leave(chat) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.leave(chat) }
    if (state.chat != chat || state.phase == VideoNotePhase.Idle) return
    Column(Modifier.fillMaxWidth().excludeModuleGestures(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (state.phase in listOf(VideoNotePhase.Starting, VideoNotePhase.Recording, VideoNotePhase.Paused)) {
            val view = remember(context) { PreviewView(context).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE; scaleType = PreviewView.ScaleType.FILL_CENTER } }
            AndroidView(factory = { view }, modifier = Modifier.size(220.dp).clip(CircleShape), onRelease = { vm.detach() })
            DisposableEffect(view, owner, state.front) { vm.attach(owner, view); onDispose { vm.detach() } }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).background(if (state.phase == VideoNotePhase.Paused) DefaultDesign.colors.secondary else androidx.compose.ui.graphics.Color.Red, CircleShape))
                Text("${state.millis / 1000}s / 60s", style = MaterialTheme.typography.labelSmall)
            }
            Text(if (state.locked) "Recording locked" else "Slide left to cancel · slide up to lock", style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultAction("Switch camera", vm::switchCamera, compact = true)
                if (state.locked) {
                    DefaultAction(if (state.phase == VideoNotePhase.Paused) "Resume" else "Pause", vm::pause, compact = true)
                    DefaultAction("Stop & preview", { vm.finish(false) }, compact = true)
                }
            }
        } else if (state.path != null) {
            LocalVideoPreview(state.path!!, "draft:$chat")
        }
        if (state.phase in listOf(VideoNotePhase.Encoding, VideoNotePhase.Sending)) Text(if (state.phase == VideoNotePhase.Encoding) "Preparing video note…" else "Submitting video note…", style = MaterialTheme.typography.labelSmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == VideoNotePhase.Preview || (state.phase == VideoNotePhase.Failed && state.path != null)) DefaultAction("Send video note", vm::send, compact = true)
            if (state.phase != VideoNotePhase.Sending) DefaultAction("Discard video note", { vm.cancel() }, compact = true)
            if (state.phase == VideoNotePhase.Failed && vm.captureRetained) DefaultAction("Retry preparation", vm::retryPreparation, compact = true)
            if (state.phase == VideoNotePhase.Failed) DefaultAction("Camera access settings", { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))) }, compact = true)
        }
    }
}

@Composable
private fun LocalVideoPreview(path: String, key: String) {
    val vm = LocalVideoNotes.current ?: return
    val state by vm.playback.state.collectAsStateWithLifecycle()
    val active = state.key == key
    DisposableEffect(key) { onDispose { vm.playback.stop(key) } }
    Box(Modifier.size(220.dp).clip(CircleShape).clickable { if (active) vm.playback.toggle() else vm.playback.start(key, path, false) }, contentAlignment = Alignment.Center) {
        if (active && state.player != null) CirclePlayer(state.player!!)
        else Text("Preview", color = DefaultDesign.colors.accent)
    }
}

@Composable
fun VideoNoteMessage(item: MediaItem, media: MediaViewModel, visible: Boolean, autoCandidate: Boolean,
    delivery: Delivery?, canRetry: Boolean, onRetry: () -> Unit, onCancel: () -> Unit, onReaction: () -> Unit, onActions: () -> Unit, onOpen: (() -> Unit)? = null) {
    val vm = LocalVideoNotes.current ?: return
    val transfer by remember(media, item.media.file.id, item.media.thumbnail?.id) { media.observe(item) }.collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val key = "${transfer.session}:${item.chatId}:${item.messageId}"
    val playback by remember(key, vm) { vm.playback.state.map { if (it.key == key) it else VideoNotePlaybackState() }.distinctUntilChanged() }.collectAsStateWithLifecycle(initialValue = VideoNotePlaybackState())
    val autoplay by vm.autoplay.collectAsStateWithLifecycle()
    val reduced = LocalAppearance.current.reducedMotion
    val viewerOpen by vm.playback.viewerOpen.collectAsStateWithLifecycle()
    val file = transfer.file
    val path = file?.path?.takeIf { file.complete }
    var pendingPlay by remember(key) { mutableStateOf(false) }
    val thumbnail = transfer.thumbnail
    val context = LocalContext.current
    val image by produceState<DecodedMediaImage?>(null, thumbnail?.path, path, transfer.session) {
        value = thumbnail?.path?.let { MediaImages.load(it, 320, transfer.session) }
            ?: path?.let { MediaImages.loadVideoUri(context, android.net.Uri.fromFile(java.io.File(it)).toString(), transfer.session, 320) }
    }
    LaunchedEffect(item.media.file.id, item.media.thumbnail?.id, transfer.session) {
        item.media.thumbnail?.let { media.preview(item.copy(media = item.media.copy(file = it, thumbnail = null))) }
        if (item.media.file.complete) media.preview(item)
    }
    LaunchedEffect(visible, autoCandidate, autoplay, reduced, path, pendingPlay, viewerOpen) {
        if (viewerOpen) { pendingPlay = false; vm.playback.stop(key) }
        else if (!visible) { pendingPlay = false; vm.playback.stop(key) }
        else if (pendingPlay && path != null) { pendingPlay = false; vm.playback.start(key, path, false) }
        else if (autoCandidate && autoplay && !reduced && path != null && playback.key.isEmpty()) vm.playback.start(key, path, true)
        else if ((!autoCandidate || !autoplay || reduced) && vm.playback.state.value.key == key && vm.playback.state.value.automatic) vm.playback.stop(key)
    }
    DisposableEffect(key) { onDispose { vm.playback.stop(key) } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.playback.stop(key) }
    Column(Modifier.widthIn(max = 240.dp).excludeModuleGestures(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f)) {
        Box(Modifier.fillMaxSize().clip(CircleShape).combinedClickable(onLongClick = onActions, onDoubleClick = onReaction, onClick = {
            if (onOpen != null) onOpen()
            else if (playback.key == key) vm.playback.toggle()
            else if (path != null) vm.playback.start(key, path, false)
            else { pendingPlay = true; media.download(item) }
        }), contentAlignment = Alignment.Center) {
            if (image != null) androidx.compose.foundation.Image(image!!.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            if (playback.key == key && playback.player != null) CirclePlayer(playback.player!!)
            if (file?.active == true) Text("${(file.progress * 100).toInt()}%", color = DefaultDesign.colors.accent)
            else if (playback.failed) Text("Retry", color = DefaultDesign.colors.accent)
            else if (playback.key.isEmpty() || !playback.playing) VideoNotePlayGlyph()
            if (delivery == Delivery.Sending) {
                val upload = file ?: item.media.file
                Text(if (upload.size > 0) "${(100 * upload.uploaded / upload.size).coerceIn(0, 100)}%" else "Sending…", Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
        VideoNoteDuration(item.media.duration, Modifier.align(Alignment.BottomStart).padding(start = 6.dp, bottom = 4.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (delivery == Delivery.Failed && canRetry) DefaultAction("Retry send", onRetry, compact = true)
            if (delivery == Delivery.Sending) DefaultAction("Cancel", onCancel, compact = true)
            if (file?.active == true && delivery != Delivery.Sending) DefaultAction("Pause download", { media.pause(item.media.file.id) }, compact = true)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun CirclePlayer(player: androidx.media3.exoplayer.ExoPlayer) {
    AndroidView(factory = { context -> android.view.TextureView(context).apply { player.setVideoTextureView(this) } },
        update = { player.setVideoTextureView(it) }, modifier = Modifier.fillMaxSize(), onRelease = { player.clearVideoTextureView(it) })
}
