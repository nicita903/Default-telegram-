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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*

@Composable
fun SettingsScreen(viewModel: PreferencesViewModel, onPrivacy: () -> Unit) {
    val theme by viewModel.theme.collectAsStateWithLifecycle()
    val previews by viewModel.hidePreviews.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        ThemeMode.entries.forEach { mode ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = theme == mode.name, onClick = { viewModel.theme(mode) })
                TextButton(onClick = { viewModel.theme(mode) }) { Text("${mode.name} theme") }
            }
        }
        HorizontalDivider()
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = onPrivacy) { Text("Ghost Mode") }
        PreferenceToggle("Hide notification previews", previews, viewModel::hidePreviews)
        Text("Preview preference only. Notifications are not implemented in this phase.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider()
        Text("About DEFAULT", style = MaterialTheme.typography.titleMedium)
        Text("Foundation · 0.1.0\nSample conversations stay in memory. No account is connected. Appearance preferences survive activity recreation; all controls remain demonstrations.")
        Text("Telegram cloud chats are not end-to-end encrypted. Only supported Secret Chats offer Telegram's end-to-end encryption model.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun PrivacyScreen(contacts: List<Chat>, viewModel: PreferencesViewModel) {
    val preferences by viewModel.ghostPreferences.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
fun PreferenceToggle(title: String, selected: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = selected, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = selected, onCheckedChange = null)
    }
}
