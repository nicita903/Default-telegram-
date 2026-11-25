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
    Text("Call connection privacy", style = MaterialTheme.typography.titleMedium)
    Text("Peer-to-peer · " + if (state.busy) "Checking / applying" else state.base ?: "Custom or unavailable",
        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Everyone", "Contacts", "Nobody").forEach { label ->
            DefaultAction(label, { privacy.setDefault(label) }, enabled = !state.busy && state.rules != null, emphasized = state.base == label)
        }
    }
    Text("Changes the default P2P rule; existing exceptions are retained (" + state.exceptions + "). Relay routing can hide your IP address from the other participant; relay services may still see connection metadata.",
        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
    state.error?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall) }
    DefaultAction("Refresh call privacy", privacy::refresh, enabled = !state.busy)
}
