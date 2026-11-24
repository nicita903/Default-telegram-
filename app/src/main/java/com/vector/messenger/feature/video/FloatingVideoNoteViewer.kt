package com.vector.messenger.feature.video

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.*
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.media.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Transient projection of this conversation's loaded history, never a second media store. */
@Composable
fun FloatingVideoNoteViewer(items: List<MediaItem>, initialId: Long, media: MediaViewModel,
    hasOlder: Boolean, loadingOlder: Boolean, historyError: String?, loadOlder: () -> Unit, onClose: () -> Unit) {
    val vm = LocalVideoNotes.current ?: return
    val openedSession = remember { media.state.value.session }
    val mediaState by media.state.collectAsStateWithLifecycle()
    val notes = remember(items) { items.filter { it.media.kind == MediaKind.VideoNote } }
    var currentId by rememberSaveable(initialId) { mutableLongStateOf(initialId) }
    val item = notes.find { it.messageId == currentId }
    val index = notes.indexOfFirst { it.messageId == currentId }
    val settings = LocalAppearance.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val shift = remember { Animatable(0f) }
    val opacity = remember { Animatable(1f) }
    var changing by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(true) }
    val currentNotes by rememberUpdatedState(notes)
    val latestOlder by rememberUpdatedState(hasOlder)
    val latestLoading by rememberUpdatedState(loadingOlder)
    val latestLoad by rememberUpdatedState(loadOlder)
    LaunchedEffect(item, mediaState.session, mediaState.authenticated) {
        if (item == null || mediaState.session != openedSession || !mediaState.authenticated) onClose()
    }
    DisposableEffect(vm) {
        vm.playback.viewer(true)
        onDispose { vm.playback.stop(); vm.playback.viewer(false) }
    }
    if (item == null || mediaState.session != openedSession || !mediaState.authenticated) return
    fun navigate(delta: Int) {
        if (changing) return
        val position = currentNotes.indexOfFirst { it.messageId == currentId }
        val next = currentNotes.getOrNull(position + delta)
        changing = true
        scope.launch {
            try {
                if (next == null) {
                    if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    if (delta < 0 && latestOlder && !latestLoading) latestLoad()
                    shift.animateTo(-delta * 8f, VectorMotionSpec.feedback(settings))
                    shift.animateTo(0f, VectorMotionSpec.settle(settings))
                } else {
                    vm.playback.stop()
                    kotlinx.coroutines.coroutineScope {
                        launch { shift.animateTo(-delta * 24f * VectorMotionSpec.strength(settings), VectorMotionSpec.feedback(settings)) }
                        launch { opacity.animateTo(0f, VectorMotionSpec.feedback(settings)) }
                    }
                    currentId = next.messageId
                    shift.snapTo(delta * 24f * VectorMotionSpec.strength(settings))
                    kotlinx.coroutines.coroutineScope {
                        launch { shift.animateTo(0f, VectorMotionSpec.feedback(settings)) }
                        launch { opacity.animateTo(1f, VectorMotionSpec.feedback(settings)) }
                    }
                }
            } finally { changing = false }
        }
    }
    LaunchedEffect(currentId, notes.map { it.media.thumbnail?.id }) {
        listOfNotNull(notes.getOrNull(index - 1), notes.getOrNull(index + 1)).forEach { adjacent ->
            adjacent.media.thumbnail?.let { thumbnail -> media.preview(adjacent.copy(media = adjacent.media.copy(file = thumbnail, thumbnail = null))) }
        }
    }
    DefaultFloatingOverlay(onDismiss = onClose) {
        BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().excludeModuleGestures().clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClose)
            .semantics { paneTitle = "Video circle viewer" }, contentAlignment = Alignment.Center) {
            // Constraints already exclude system bars; reserve room for the existing controls.
            val diameter = minOf(330.dp, maxWidth - 32.dp, maxHeight - 240.dp).coerceAtLeast(1.dp)
            DefaultIconControl(Icons.Default.Close, "Close video viewer", onClose,
                Modifier.align(Alignment.TopStart).padding(12.dp), quiet = true)
            val activeDot = when { notes.size <= 1 -> 1; index == 0 -> 0; index == notes.size - 1 -> 2; else -> 1 }
            Column(Modifier.align(Alignment.TopEnd).padding(top = 20.dp, end = 20.dp).semantics {
                contentDescription = "Video ${index + 1} of ${notes.size} loaded"
                customActions = listOf(CustomAccessibilityAction("Previous video") { navigate(-1); true },
                    CustomAccessibilityAction("Next video") { navigate(1); true })
            }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                repeat(3) { dot ->
                    Box(Modifier.size(5.dp).background(if (dot == activeDot) DefaultDesign.colors.accent
                        else DefaultDesign.colors.secondary.copy(alpha = .55f), CircleShape))
                }
            }
            Column(Modifier.width(diameter)
                .clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {}
                .graphicsLayer { translationY = shift.value * density; alpha = opacity.value },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                key(currentId) {
                    FloatingCircleContent(item, media, vm.playback, muted, { muted = !muted; vm.playback.mute() },
                        diameter = diameter, onNavigate = ::navigate)
                }
                if (hasOlder || historyError != null) DefaultAction(
                    if (loadingOlder) "Loading older history…" else if (historyError != null) "Retry older history" else "Load older history",
                    loadOlder, enabled = !loadingOlder, compact = true)
            }
        }
    }
}

@Composable
private fun FloatingCircleContent(item: MediaItem, media: MediaViewModel, player: VideoNotePlayback,
    muted: Boolean, onMute: () -> Unit, diameter: androidx.compose.ui.unit.Dp,
    onNavigate: (Int) -> Unit) {
    val transfer by remember(media, item.media.file.id) { media.observe(item) }.collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val playback by player.state.collectAsStateWithLifecycle()
    val error by media.error.collectAsStateWithLifecycle()
    val key = "viewer:${transfer.session}:${item.chatId}:${item.messageId}"
    val path = transfer.file?.takeIf { it.complete }?.path
    val thumbnail = transfer.thumbnail
    val context = LocalContext.current
    val image by produceState<DecodedMediaImage?>(null, thumbnail?.path, path, transfer.session) {
        value = thumbnail?.path?.let { MediaImages.load(it, 400, transfer.session) }
            ?: path?.let { MediaImages.loadVideoUri(context, android.net.Uri.fromFile(java.io.File(it)).toString(), transfer.session, 400) }
    }
    val currentMuted by rememberUpdatedState(muted)
    LaunchedEffect(item.media.file.id) {
        item.media.thumbnail?.let { media.preview(item.copy(media = item.media.copy(file = it, thumbnail = null))) }
        media.download(item)
    }
    DisposableEffect(item.media.file.id) {
        val wasActive = media.state.value.files[item.media.file.id]?.active == true
        onDispose {
            player.stop(key)
            val file = media.state.value.files[item.media.file.id]
            if (!wasActive && file?.active == true && !file.complete) media.pause(item.media.file.id)
        }
    }
    LifecycleStartEffect(path, key) {
        if (path != null) { player.start(key, path, true); if (!currentMuted) player.mute() }
        onStopOrDispose { player.stop(key) }
    }
    val navigate by rememberUpdatedState(onNavigate)
    val settings = LocalAppearance.current
    val haptic = LocalHapticFeedback.current
    val active = playback.key == key
    val position = if (active) playback.position else 0L
    val duration = if (active && playback.duration > 0) playback.duration else item.media.duration * 1000L
    Box(Modifier.size(diameter)) {
    Box(Modifier.size(diameter).align(Alignment.Center).clip(CircleShape)
        .pointerInput(key) {
            var total = Offset.Zero
            detectDragGestures(onDragStart = { total = Offset.Zero }, onDragCancel = { total = Offset.Zero }, onDragEnd = {
                val vertical = abs(total.y) > abs(total.x)
                if (vertical && abs(total.y) >= 48.dp.toPx()) navigate(if (total.y < 0) 1 else -1)
                else if (!vertical && abs(total.x) >= 40.dp.toPx()) {
                    player.seek(player.state.value.position + if (total.x > 0) 5000 else -5000)
                    if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }) { change, amount -> change.consume(); total += amount }
        }.clickable(onClickLabel = if (active && playback.playing) "Pause video" else "Play video") {
            if (active && !playback.failed) player.toggle()
            else if (path != null) { player.stop(key); player.start(key, path, true); if (!muted) player.mute() }
            else media.download(item)
        }, contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        if (active && playback.player != null) CirclePlayer(playback.player!!)
        if (transfer.file?.active == true) Text("${(transfer.file!!.progress * 100).toInt()}%", color = DefaultDesign.colors.accent)
        else if (!active || !playback.playing) VideoNotePlayGlyph()
    }
    }
    Text("${videoNoteTime(position)} / ${videoNoteTime(duration)}", Modifier.padding(top = 12.dp),
        color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
    Box(Modifier.width(diameter)) { VideoSeekBar(position, duration) { player.seek(it) } }
    DefaultIconControl(if (muted) VideoNoteIcons.Muted else VideoNoteIcons.Audible,
        if (muted) "Unmute video" else "Mute video", onMute, quiet = true)
    if (error != null || playback.failed && active) {
        Text(if (playback.failed && active) "Playback unavailable" else error.orEmpty(), style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
        DefaultAction("Retry download", { media.download(item) }, compact = true)
    }
}

@Composable
private fun VideoSeekBar(position: Long, duration: Long, seek: (Long) -> Unit) {
    val currentSeek by rememberUpdatedState(seek)
    val currentDuration by rememberUpdatedState(duration)
    val accent = DefaultDesign.colors.accent
    val track = DefaultDesign.colors.secondary.copy(alpha = .3f)
    val fraction = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Canvas(Modifier.fillMaxWidth().height(28.dp).semantics {
        contentDescription = "Video progress"
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        setProgress { currentSeek((it.coerceIn(0f, 1f) * currentDuration).toLong()); true }
    }.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume()
            fun update(x: Float) { currentSeek((x / size.width.coerceAtLeast(1) * currentDuration).toLong()) }
            update(down.position.x)
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break
                val change = event.changes.single(); change.consume(); update(change.position.x)
                if (!change.pressed) break
            }
        }
    }) {
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx())
        drawLine(accent, Offset(0f, size.height / 2), Offset(size.width * fraction, size.height / 2), 2.dp.toPx())
        drawCircle(accent, 4.dp.toPx(), Offset(size.width * fraction, size.height / 2))
    }
}
