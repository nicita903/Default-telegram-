package com.vector.messenger.calling

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.design.*

@Composable
fun CallPrivacySettings() {
    val privacy = LocalVoiceCalls.current?.privacy ?: return
    val state by privacy.state.collectAsStateWithLifecycle()
    LaunchedEffect(privacy) { privacy.refresh() }
    var choose by remember { mutableStateOf(false) }
    com.vector.messenger.feature.CompactSettingsRow("Peer-to-peer call privacy", if (state.busy) "Applying…" else state.base ?: "Unavailable",
        CallIcons.Phone, enabled = !state.busy && state.rules != null) { choose = true }
    Text("Telegram · current account. Existing exceptions are retained (${state.exceptions}). Relay routing can hide your IP address from the other participant; relay services may still see connection metadata.",
        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall) }
    com.vector.messenger.feature.CompactSettingsRow("Refresh call privacy", icon = VectorIcons.Refresh, chevron = false, enabled = !state.busy) { privacy.refresh() }
    if (choose) DefaultDialog("Peer-to-peer calls", { choose = false }) {
        listOf("Everyone", "Contacts", "Nobody").forEach { label ->
            com.vector.messenger.feature.CompactSettingsRow(label, icon = CallIcons.Phone, selected = state.base == label, chevron = false,
                enabled = !state.busy && state.rules != null) { privacy.setDefault(label); choose = false }
        }
    }
}
