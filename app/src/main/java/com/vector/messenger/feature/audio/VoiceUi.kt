package com.vector.messenger.feature.audio

import com.vector.messenger.design.DesignIcon as Icon

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import androidx.lifecycle.compose.*
import com.vector.core.audio.*
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.media.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
fun Waveform(samples: List<Int>, progress: () -> Float, modifier: Modifier = Modifier, onSeek: ((Float) -> Unit)? = null) {
    val accent = DefaultDesign.colors.accent
    val inactive = DefaultDesign.colors.secondary.copy(alpha = .45f)
    val seek by rememberUpdatedState(onSeek)
    Canvas(modifier.height(30.dp).semantics {
        contentDescription = if (samples.isEmpty()) "Audio timeline; waveform unavailable" else "Audio waveform"
        if (onSeek != null) {
            progressBarRangeInfo = ProgressBarRangeInfo(progress().coerceIn(0f, 1f), 0f..1f)
            setProgress { seek?.invoke(it.coerceIn(0f, 1f)); true }
        }
    }.then(if (onSeek == null) Modifier else Modifier.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(); down.consume()
            seek?.invoke((down.position.x / size.width).coerceIn(0f, 1f))
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size != 1) break
                val change = event.changes.single()
                if (!change.pressed) { change.consume(); break }
                change.consume(); seek?.invoke((change.position.x / size.width).coerceIn(0f, 1f))
            }
        }
    })) {
        val bars = samples.take(100)
        val count = bars.size.coerceAtLeast(1)
        val step = size.width / count
        for (index in 0 until count) {
            val height = if (bars.isEmpty()) 2.dp.toPx() else max(2.dp.toPx(), size.height * bars[index].coerceIn(0, 31) / 31f)
            val x = (index + .5f) * step
            drawLine(if ((index + .5f) / count <= progress()) accent else inactive, Offset(x, (size.height - height) / 2),
                Offset(x, (size.height + height) / 2), min(2.dp.toPx(), step * .55f), androidx.compose.ui.graphics.StrokeCap.Round)
        }
    }
}

private fun durationText(ms: Long): String = "%d:%02d".format(java.util.Locale.ROOT, ms.coerceAtLeast(0) / 60000, ms.coerceAtLeast(0) / 1000 % 60)

@Composable
fun VoiceAttachment(item: MediaItem, media: MediaViewModel) {
    val audio = LocalAudioViewModel.current
    val transfer by remember(media, item.media.file.id) { media.observe(item) }.collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val playback by remember(audio, item.chatId, item.messageId) { audio?.observe(item) ?: kotlinx.coroutines.flow.flowOf(AudioPlaybackState()) }
        .collectAsStateWithLifecycle(initialValue = AudioPlaybackState())
    val duration = playback.duration.takeIf { it > 0 } ?: item.media.duration * 1000L
    val progress = if (duration > 0) playback.position.toFloat() / duration else 0f
    Column(Modifier.fillMaxWidth().excludeModuleGestures().testTag("voice_attachment")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultAction(if (playback.phase == AudioPhase.Playing) "Pause" else if (playback.phase == AudioPhase.Loading) "Stop loading" else "Play",
                { audio?.toggle(item) }, enabled = audio != null, compact = true)
            Waveform(item.media.waveform, { progress }, Modifier.weight(1f),
                onSeek = if (playback.item != null && duration > 0) ({ value -> audio?.seek(value) }) else null)
            DefaultAction("${playback.speed}×", { audio?.speed() }, enabled = playback.item != null, compact = true)
        }
        Text("${durationText(playback.position)} / ${durationText(duration)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        val file = transfer.file
        if (file?.active == true || file?.uploading == true) {
            val fraction = if (file.uploading && file.size > 0) (file.uploaded.toDouble() / file.size).coerceIn(0.0, 1.0).toFloat() else file.progress
            Text("${if (file.uploading) "Uploading" else "Downloading"} ${(fraction * 100).toInt()}%", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(progress = { fraction }, Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
        }
        playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
    LaunchedEffect(item.media.file.id, transfer.session) { if (item.media.file.complete) media.preview(item) }
}

@Composable
fun FloatingAudioPlayer(onOpen: (Long) -> Unit) {
    val vm = LocalAudioViewModel.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val item = state.item ?: run { com.vector.messenger.feature.music.MusicMiniPlayer(); return }
    Row(Modifier.fillMaxWidth().excludeModuleGestures().background(DefaultDesign.colors.raised).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f).clickable { onOpen(item.chatId) }.semantics { contentDescription = "Return to audio conversation" }) {
            Text(item.media.name, color = DefaultDesign.colors.text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            Text("${durationText(state.position)} / ${durationText(state.duration)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
            LinearProgressIndicator(progress = { if (state.duration > 0) state.position.toFloat() / state.duration else 0f }, Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
        }
        DefaultAction(if (state.phase == AudioPhase.Playing) "Pause" else if (state.phase == AudioPhase.Loading) "Stop loading" else "Play", { vm.toggle(item) }, compact = true)
        DefaultIconControl(Icons.Default.Close, "Stop audio", vm::stop, quiet = true)
    }
}

@Composable
fun VoiceMicrophone(chatId: Long, reply: Long?, enabled: Boolean, onTap: (() -> Unit)? = null, composerStyle: Boolean = false) {
    val vm = LocalVoiceViewModel.current ?: return
    val context = LocalContext.current
    val currentTap by rememberUpdatedState(onTap)
    val currentReply by rememberUpdatedState(reply)
    val currentEnabled by rememberUpdatedState(enabled)
    val state by vm.state.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val appearance = LocalAppearance.current
    val density = LocalDensity.current.density
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed -> if (!allowed) vm.permissionDenied() }
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        val started = state.chatId == chatId && state.phase in listOf(RecordingPhase.Recording, RecordingPhase.Locked)
        if (started && !pulse && appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        pulse = started
    }
    fun allowed() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val buttonColors = DefaultDesign.colors
    Box(Modifier.size(48.dp).excludeModuleGestures().then(if (composerStyle) Modifier.composerRecordingSurface(enabled)
        else Modifier.background(buttonColors.accent, DefaultDesign.controlShape))
        .semantics {
            contentDescription = "Record voice message"
            customActions = listOf(CustomAccessibilityAction("Start locked voice recording") { if (currentEnabled) { if (allowed()) vm.start(chatId, currentReply, locked = true) else permission.launch(Manifest.permission.RECORD_AUDIO) }; true })
            onClick(label = if (onTap != null) "Switch to video messages" else "Start locked recording") {
                if (currentEnabled && currentTap != null) { currentTap?.invoke(); return@onClick true }
                if (currentEnabled) { if (allowed()) vm.start(chatId, currentReply, locked = true) else permission.launch(Manifest.permission.RECORD_AUDIO) }; true
            }
        }.pointerInput(chatId, enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = true)
                val machine = VoiceGesture(72 * density, 64 * density)
                var activated = false
                var completed = false
                try {
                    var eligible = true
                    var tapped = false
                    val finishedEarly = withTimeoutOrNull(180) {
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (event.changes.size != 1 || change == null || change.isConsumed || (change.position - down.position).getDistance() > 12 * density) {
                                eligible = false; break
                            }
                            if (!change.pressed) { tapped = true; break }
                        }
                        true
                    }
                    if (finishedEarly == true || !eligible) { if (eligible && tapped) currentTap?.invoke(); return@awaitEachGesture }
                    if (!allowed()) { permission.launch(Manifest.permission.RECORD_AUDIO); return@awaitEachGesture }
                    if (!currentEnabled) return@awaitEachGesture
                    vm.start(chatId, currentReply); activated = true
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.size != 1) break
                        val change = event.changes.single()
                        if (change.isConsumed) break
                        val delta = change.position - down.position
                        change.consume()
                        when (machine.move(delta.x, delta.y)) {
                            VoiceGestureAction.Cancel -> { vm.cancel(); completed = true; break }
                            VoiceGestureAction.Lock -> vm.lock()
                            else -> Unit
                        }
                        if (!change.pressed) {
                            if (machine.release() == VoiceGestureAction.Send) vm.send()
                            completed = true; break
                        }
                    }
                } finally { if (activated && !completed) vm.cancel() }
            }
        }, contentAlignment = Alignment.Center) {
        Icon(VectorIcons.Microphone, null, Modifier.size(if (composerStyle) 20.dp else 22.dp), tint = if (composerStyle) buttonColors.text else buttonColors.onAccent)
    }
}

@Composable
fun VoiceRecordingPanel(chatId: Long) {
    val vm = LocalVoiceViewModel.current ?: return
    val context = LocalContext.current
    DisposableEffect(chatId) { onDispose { vm.leave(chatId) } }
    val state by vm.state.collectAsStateWithLifecycle()
    if (state.chatId != null && state.chatId != chatId) return
    if (state.phase == RecordingPhase.Idle && state.error == null) return
    Column(Modifier.fillMaxWidth().excludeModuleGestures().padding(horizontal = 16.dp, vertical = 6.dp)) {
        if (state.phase in listOf(RecordingPhase.Starting, RecordingPhase.Recording, RecordingPhase.Locked)) {
            Text("${if (state.phase == RecordingPhase.Locked) "Recording locked" else "Recording"} · ${durationText(state.duration)}", color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelSmall)
            Waveform(voiceWaveform(state.amplitudes), { 1f }, Modifier.fillMaxWidth())
            Text(if (state.phase == RecordingPhase.Locked) "Send when ready" else "Slide left to cancel · slide up to lock", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        } else if (state.phase != RecordingPhase.Idle) Text(when (state.phase) {
            RecordingPhase.Sending -> "Submitting to Telegram…"
            RecordingPhase.Finishing -> "Preparing voice message…"
            else -> "Voice message"
        }, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (state.permissionRequired) DefaultAction("Microphone settings", {
            context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
        }, compact = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.phase == RecordingPhase.Locked || (state.phase == RecordingPhase.Failed && state.retryAvailable))
                DefaultAction(if (state.phase == RecordingPhase.Failed) "Retry voice message" else "Send voice message", vm::send, compact = true)
            if (state.phase !in listOf(RecordingPhase.Finishing, RecordingPhase.Sending))
                DefaultAction(if (state.phase == RecordingPhase.Uncertain) "Close" else "Cancel recording", { vm.cancel() }, compact = true)
        }
    }
}

@Composable
fun AudioLifecycle() {
    val audio = LocalAudioViewModel.current
    val voice = LocalVoiceViewModel.current
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, audio, voice) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { voice?.cancel(); audio?.background() } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); voice?.cancel(); audio?.background() }
    }
}
