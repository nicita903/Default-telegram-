package com.vector.messenger.calling

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.delay

@Composable
fun VoiceCallButton(user: Long) {
    val calls = LocalVoiceCalls.current ?: return
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.outgoing(user) else calls.permissionDenied()
    }
    CallControl(CallIcons.Phone, "Start voice call", {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.outgoing(user)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, diameter = 48.dp, quiet = true)
}

@Composable
fun VoiceCallHost() {
    val calls = LocalVoiceCalls.current ?: return
    val state by calls.state.collectAsStateWithLifecycle()
    var minimized by rememberSaveable { mutableStateOf(false) }
    var options by remember { mutableStateOf(false) }
    var securityInfo by remember(state.id, state.security?.verificationEmojis) { mutableStateOf(false) }
    val security = state.security?.takeIf { it.callId == state.id && it.available &&
        state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting) }
    var foreground by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { foreground = true }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { foreground = false }
    val terminal = state.phase in listOf(VoiceCallPhase.Ended, VoiceCallPhase.Failed)
    LaunchedEffect(state.phase) {
        if (state.phase == VoiceCallPhase.Idle || state.phase == VoiceCallPhase.Incoming || state.phase == VoiceCallPhase.Requesting && state.id == 0) minimized = false
        if (terminal) { options = false; if (minimized) calls.dismiss() }
    }
    if (state.phase == VoiceCallPhase.Idle) return
    val context = LocalContext.current
    val settings = LocalAppearance.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.answer() else calls.end("Microphone permission was denied.")
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) calls.bluetooth() }
    val running = foreground && state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting)
    val seconds by produceState(0L, state.connectedAt, running) {
        if (state.connectedAt <= 0) value = 0
        else if (running) while (true) { value = (SystemClock.elapsedRealtime() - state.connectedAt) / 1000; delay(1000) }
    }
    val status = if (state.phase == VoiceCallPhase.Active) callTime(seconds) else callStatus(state.phase)
    if (minimized) {
        if (!terminal) FloatingCallIndicator(state, if (state.connectedAt > 0) callTime(seconds) else status) { minimized = false }
        return
    }
    val output = state.audioOutput
    val speaker = output == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    val route = when (output) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Speaker on"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth headset"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headset"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        else -> "System audio output"
    }
    val controllable = state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting)
    fun answer() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.answer()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    fun bluetooth() {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
        else calls.bluetooth()
    }
    DefaultFloatingOverlay(onDismiss = { if (terminal) calls.dismiss() else minimized = true }) {
        FloatingBackdropHost {
            BoxWithConstraints(Modifier.fillMaxSize().designCanvas(DefaultDesign.colors.canvas).safeDrawingPadding().excludeModuleGestures()) {
                val short = maxHeight < 500.dp
                val avatarSize = minOf(160.dp, (maxHeight * .34f).coerceAtLeast(80.dp))
                Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    CallControl(Icons.AutoMirrored.Filled.ArrowBack, if (terminal) "Close call" else "Minimize call",
                        { if (terminal) calls.dismiss() else minimized = true }, diameter = 48.dp, quiet = true)
                    if (controllable) CallControl(Icons.Default.MoreVert, "Call audio options", { options = true }, diameter = 48.dp, quiet = true)
                }
                Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).padding(top = 64.dp, bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        var appeared by remember { mutableStateOf(false) }
                        LaunchedEffect(Unit) { appeared = true }
                        val avatarScale = animateFloatAsState(if (appeared) 1f else VectorMotionSpec.pressScale(.97f, settings),
                            VectorMotionSpec.transition(settings), label = "Call avatar entrance")
                        Box(Modifier.graphicsLayer { scaleX = avatarScale.value; scaleY = avatarScale.value }) {
                            if (state.user > 0) TelegramAvatar(state.name, null, avatarSize, state.user, true)
                        }
                        Spacer(Modifier.height(if (short) 12.dp else 22.dp))
                        Text(state.name.ifBlank { "Telegram voice call" }, color = DefaultDesign.colors.text,
                            style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(6.dp))
                        if (state.phase == VoiceCallPhase.Active) Text(callTime(seconds), color = DefaultDesign.colors.secondary,
                            style = MaterialTheme.typography.bodyLarge)
                        else AnimatedContent(state.phase, transitionSpec = {
                            fadeIn(VectorMotionSpec.transition(settings)) togetherWith fadeOut(VectorMotionSpec.exit(settings))
                        }, label = "Call status") { phase ->
                            CallingStatus(callStatus(phase), phase in listOf(VoiceCallPhase.Requesting, VoiceCallPhase.Ringing, VoiceCallPhase.Connecting) && foreground)
                        }
                        if (security != null) Row(Modifier.padding(top = 12.dp).heightIn(min = 48.dp)
                            .clickable(onClickLabel = "Compare call encryption emojis") { securityInfo = true }
                            .padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                            security.verificationEmojis.forEach { Text(it, fontSize = 26.sp) }
                        }
                        state.error?.let { Text(it, Modifier.padding(top = 10.dp), color = DefaultDesign.colors.secondary,
                            textAlign = TextAlign.Center, style = MaterialTheme.typography.bodySmall) }
                        Spacer(Modifier.height(20.dp))
                    }
                    }
                    Spacer(Modifier.height(if (short) 8.dp else 24.dp))
                    if (state.phase == VoiceCallPhase.Incoming) {
                        Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                            CallControl(CallIcons.HangUp, "Decline call", { calls.end() }, diameter = 68.dp, destructive = true)
                            if (!state.video) CallControl(CallIcons.Phone, "Accept call", ::answer, diameter = 68.dp, active = true)
                        }
                    } else if (terminal) {
                        CallControl(Icons.Default.Check, "Close call", calls::dismiss, diameter = 56.dp, quiet = true)
                    } else {
                        if (controllable) Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                            CallControl(if (state.muted) CallIcons.MicrophoneOff else VectorIcons.Microphone,
                                if (state.muted) "Unmute microphone" else "Mute microphone", calls::mute,
                                active = state.muted, value = if (state.muted) "Microphone muted" else "Microphone on")
                            CallControl(CallIcons.Speaker, if (speaker) "Switch to earpiece" else "Switch to speaker", calls::speaker,
                                active = speaker, value = route)
                        }
                        Spacer(Modifier.height(if (short) 12.dp else 24.dp))
                        CallControl(CallIcons.HangUp, "End call", { calls.end() }, diameter = 68.dp,
                            destructive = true, enabled = state.phase != VoiceCallPhase.Ending)
                    }
                }
            }
            if (securityInfo && security != null) DefaultDialog("ENCRYPTED CALL", { securityInfo = false }) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
                    security.verificationEmojis.forEach { Text(it, fontSize = 26.sp) }
                }
                Text("Compare these four emojis with the person you're calling. If they match, it helps confirm that your call uses the same encryption key on both devices.",
                    color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
                DefaultIconControl(Icons.Default.Close, "Close security information", { securityInfo = false }, quiet = true)
            }
            if (options && controllable) DefaultDialog("Audio output", { options = false }) {
                Text(route, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    CallControl(CallIcons.Speaker, if (speaker) "Switch to earpiece" else "Switch to speaker",
                        { calls.speaker(); options = false }, active = speaker, value = route)
                    CallControl(CallIcons.Bluetooth, "Use Bluetooth headset", { bluetooth(); options = false },
                        active = output in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET))
                }
            }
        }
    }
}

@Composable
private fun CallingStatus(text: String, waiting: Boolean) {
    val settings = LocalAppearance.current
    if (waiting && VectorMotionSpec.strength(settings) > 0) {
        val transition = rememberInfiniteTransition(label = "Calling feedback")
        val alpha = transition.animateFloat(.65f, 1f, infiniteRepeatable(tween(VectorMotionSpec.duration(900, settings)), RepeatMode.Reverse), label = "Calling opacity")
        Text(text, Modifier.graphicsLayer { this.alpha = alpha.value }, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
    } else Text(text, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun CallControl(icon: ImageVector, description: String, action: () -> Unit, diameter: Dp = 56.dp,
    active: Boolean = false, destructive: Boolean = false, quiet: Boolean = false, enabled: Boolean = true, value: String? = null) {
    val colors = DefaultDesign.colors
    val settings = LocalAppearance.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.96f, settings) else 1f,
        VectorMotionSpec.press(pressed, settings), label = "Call control press")
    val background by animateColorAsState(when {
        destructive -> MaterialTheme.colorScheme.error.copy(alpha = .76f)
        active -> colors.accent.copy(alpha = .2f)
        quiet -> Color.Transparent
        else -> colors.raised
    }, VectorMotionSpec.transition(settings), label = "Call control selection")
    Box(Modifier.size(diameter.coerceAtLeast(48.dp)).excludeModuleGestures().graphicsLayer { scaleX = scale.value; scaleY = scale.value; alpha = if (enabled) 1f else .45f }
        .clip(CircleShape).background(background)
        .semantics { contentDescription = description; if (value != null) stateDescription = value }
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClickLabel = description, onClick = action),
        contentAlignment = Alignment.Center) {
        DesignIcon(icon, null, Modifier.size(if (diameter >= 68.dp) 28.dp else 24.dp), tint = if (destructive) Color.White else if (active) colors.accent else colors.text)
    }
}

@Composable
private fun FloatingCallIndicator(state: VoiceCallState, time: String, onOpen: () -> Unit) {
    var dx by rememberSaveable { mutableFloatStateOf(0f) }
    var dy by rememberSaveable { mutableFloatStateOf(0f) }
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val maxX = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth - 80.dp).coerceAtLeast(0.dp).toPx() }
        val maxY = with(androidx.compose.ui.platform.LocalDensity.current) { (maxHeight - 100.dp).coerceAtLeast(0.dp).toPx() }
        Column(Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 12.dp)
            .graphicsLayer { translationX = dx.coerceIn(-maxX, 0f); translationY = dy.coerceIn(-64.dp.toPx(), (maxY - 64.dp.toPx()).coerceAtLeast(0f)) }
            .excludeModuleGestures().pointerInput(maxX, maxY) {
                detectDragGestures { change, amount -> change.consume(); dx = (dx + amount.x).coerceIn(-maxX, 0f); dy = (dy + amount.y).coerceIn(-64.dp.toPx(), (maxY - 64.dp.toPx()).coerceAtLeast(0f)) }
            }.semantics(mergeDescendants = true) { contentDescription = "Return to voice call"; stateDescription = time }
            .clickable(role = Role.Button, onClick = onOpen).padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            TelegramAvatar(state.name, null, 52.dp, state.user, true, onClick = onOpen)
            Text(time, Modifier.padding(top = 4.dp), color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** Observation only: routing commands remain exclusively in the existing call controller. */
@Suppress("DEPRECATION")
private fun callTime(seconds: Long) = "%02d:%02d".format(java.util.Locale.ROOT, seconds.coerceAtLeast(0) / 60, seconds.coerceAtLeast(0) % 60)
private fun callStatus(phase: VoiceCallPhase) = when (phase) {
    VoiceCallPhase.Incoming -> "Incoming call"; VoiceCallPhase.Requesting -> "Calling…"; VoiceCallPhase.Ringing -> "Ringing…"
    VoiceCallPhase.Connecting -> "Connecting…"; VoiceCallPhase.Reconnecting -> "Reconnecting…"; VoiceCallPhase.Ending -> "Ending…"
    VoiceCallPhase.Ended -> "Call ended"; VoiceCallPhase.Failed -> "Call failed"; else -> ""
}
