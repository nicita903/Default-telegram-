@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.vector.messenger.calling

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.design.*
import kotlinx.coroutines.delay

@Composable
fun VoiceCallButton(user: Long) {
    val calls = LocalVoiceCalls.current ?: return
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.outgoing(user) else calls.permissionDenied()
    }
    DefaultAction("VOICE CALL", {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.outgoing(user)
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }, compact = true)
}

@Composable
fun VoiceCallHost() {
    val calls = LocalVoiceCalls.current ?: return
    val state by calls.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) calls.answer() else calls.end("Microphone permission was denied.")
    }
    val bluetoothPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) calls.bluetooth() }
    if (state.phase == VoiceCallPhase.Idle) return
    val seconds by produceState(0L, state.connectedAt) {
        if (state.connectedAt > 0) while (true) { value = (SystemClock.elapsedRealtime() - state.connectedAt) / 1000; delay(1000) }
    }
    DefaultFloatingOverlay(onDismiss = { if (state.phase in listOf(VoiceCallPhase.Ended, VoiceCallPhase.Failed)) calls.dismiss() else calls.end() }) {
        Column(Modifier.fillMaxSize().designCanvas(DefaultDesign.colors.canvas).safeDrawingPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (state.user > 0) TelegramAvatar(state.name, null, 140.dp, state.user, true)
            Spacer(Modifier.height(20.dp))
            Text(state.name.ifBlank { "Telegram voice call" }, color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleLarge)
            Text(when (state.phase) {
                VoiceCallPhase.Incoming -> "Incoming call"; VoiceCallPhase.Requesting -> "Requesting…"; VoiceCallPhase.Ringing -> "Ringing…"
                VoiceCallPhase.Connecting -> "Connecting media…"; VoiceCallPhase.Active -> "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
                VoiceCallPhase.Reconnecting -> "Reconnecting…"; VoiceCallPhase.Ending -> "Ending…"; VoiceCallPhase.Ended -> "Call ended"
                VoiceCallPhase.Failed -> "Call failed"; else -> ""
            }, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium)
            state.error?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.phase == VoiceCallPhase.Incoming && !state.video) DefaultAction("Answer", {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) calls.answer()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, compact = true)
                if (state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting)) {
                    DefaultAction(if (state.muted) "Unmute" else "Mute", calls::mute, compact = true)
                    DefaultAction(if (state.speaker) "Earpiece" else "Speaker", calls::speaker, compact = true)
                    DefaultAction("Bluetooth", {
                        if (android.os.Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
                            bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT)
                        else calls.bluetooth()
                    }, compact = true)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.Center) {
                if (state.phase in listOf(VoiceCallPhase.Ended, VoiceCallPhase.Failed)) DefaultAction("Done", calls::dismiss, compact = true)
                else DefaultAction(if (state.phase == VoiceCallPhase.Incoming) "Decline" else "End call", { calls.end() },
                    enabled = state.phase != VoiceCallPhase.Ending, destructive = true, compact = true)
            }
        }
    }
}
