package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.design.*
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.math.roundToInt

@Composable
fun PrivacyScreen(contacts: List<Chat>, viewModel: PreferencesViewModel, focus: String = "") {
    val liveState by viewModel.ghostState.collectAsStateWithLifecycle()
    if (liveState.authenticated) {
        SelectiveGhostScreen(liveState, viewModel::ghostEnabled, viewModel::selectGhost, viewModel::removeGhost,
            viewModel::ghostPolicy, viewModel::refreshGhost, viewModel::applyGhostPrivacy, viewModel::keepCurrentPrivacy, focus)
        return
    }
    val preferences by viewModel.ghostPreferences.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("privacy_scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ghost Mode", style = MaterialTheme.typography.headlineSmall)
        Text("Demonstration only. These controls do not change your Telegram privacy. They reset when the app process closes.", color = MaterialTheme.colorScheme.primary)
        Text("Typing and recording suppression will apply only to this client. Online and last seen controls require Telegram privacy settings. Reading or sending messages can still temporarily expose online activity. Premium does not generally override another person's last seen restrictions or remove normal online exceptions.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        contacts.forEach { contact ->
            val value = preferences[contact.id] ?: GhostPreferences()
            HorizontalDivider()
            Text(contact.title, style = MaterialTheme.typography.titleMedium)
            PreferenceToggle("Suppress typing · ${contact.title}", value.suppressTyping) { viewModel.updateGhost(contact.id, value.copy(suppressTyping = it)) }
            PreferenceToggle("Suppress recording · ${contact.title}", value.suppressRecording) { viewModel.updateGhost(contact.id, value.copy(suppressRecording = it)) }
            PreferenceToggle("Hide online · ${contact.title}", value.hideOnline) { viewModel.updateGhost(contact.id, value.copy(hideOnline = it)) }
            PreferenceToggle("Hide last seen · ${contact.title}", value.hideLastSeen) { viewModel.updateGhost(contact.id, value.copy(hideLastSeen = it)) }
        }
    }
}

@Composable
fun PreferenceToggle(title: String, selected: Boolean, onChange: (Boolean) -> Unit) = PreferenceToggle(title, selected, true, onChange)

@Composable
fun PreferenceToggle(title: String, selected: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    CompactSettingsRow(title, checked = selected, enabled = enabled, chevron = false) { onChange(!selected) }
}
