package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vector.core.ghost.*
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures

@Composable
internal fun SelectiveGhostScreen(state: GhostState, enabled: (Boolean) -> Unit, select: (Long) -> Unit, remove: (Long) -> Unit,
    policy: (Long, ContactPrivacy) -> Unit, refresh: () -> Unit, applyPrivacy: () -> Unit, keepCurrent: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val editable = state.initialized && !state.busy
    val contacts = remember(state.contacts) { state.contacts.associateBy { it.id } }
    val available = remember(state.contacts, state.saved.contacts, query) {
        state.contacts.filter { it.id !in state.saved.contacts && (it.name.contains(query.trim(), true) || it.username?.contains(query.trim(), true) == true) }
    }
    val selected = remember(state.saved.contacts, contacts, query) {
        state.saved.contacts.keys.sorted().filter { id ->
            val contact = contacts[id]
            query.isBlank() || contact?.name?.contains(query.trim(), true) == true ||
                contact?.username?.contains(query.trim(), true) == true || id.toString().contains(query.trim())
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("privacy_scroll"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Ghost Mode", style = MaterialTheme.typography.headlineSmall)
            PreferenceToggle("Enable Ghost Mode", state.saved.enabled, editable, enabled)
            Text(if (state.busy) "Updating…" else if (state.saved.enabled && state.initialized) "Local controls active · Default only" else "Local controls off",
                style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.accent)
        }
        item {
            Text("Last Seen & Online", style = MaterialTheme.typography.titleMedium)
            Text(when {
                state.conflict -> "Server rules changed · review required"
                !state.serverLoaded -> "Server rules not confirmed"
                state.confirmed && state.saved.desiredStatus().isEmpty() -> "Current rules checked · existing exclusions may apply"
                state.confirmed -> "Selected exceptions confirmed by Telegram"
                else -> "Server changes pending"
            }, style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
            Text("Apply commits account-wide exceptions. Disabling or removing a contact changes local controls first; apply again to restore managed server rules.",
                style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultAction("Refresh privacy", refresh, enabled = !state.busy)
                DefaultAction("Apply privacy changes", applyPrivacy, enabled = editable && !state.conflict && !state.confirmed)
            }
            if (state.conflict) {
                Text("Detected rule changes need review. Keeping current rules retains existing exclusions, including earlier Ghost Mode exclusions.", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
                DefaultAction("Keep current server rules", keepCurrent, enabled = editable)
            }
            state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.accent) }
        }
        item {
            DefaultRule()
            DefaultInput(query, { query = it }, "Search Telegram contacts", Modifier.fillMaxWidth().excludeModuleGestures(), leadingIcon = Icons.Default.Search)
            Text("Selected · ${state.saved.contacts.size}", style = MaterialTheme.typography.titleMedium)
            if (state.saved.contacts.isEmpty()) Text("No contacts selected", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
        }
        items(selected, key = { "selected:$it" }, contentType = { "selected_contact" }) { id ->
            val contact = contacts[id]
            val name = contact?.name ?: "Unavailable contact"
            val value = state.saved.contacts.getValue(id)
            Column(Modifier.excludeModuleGestures()) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TelegramAvatar(name, contact?.photo, 40.dp)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        Text(contact?.username?.let { "@$it" } ?: if (contact == null) "Stored Telegram ID · $id" else "Telegram contact", style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
                    }
                    DefaultIconControl(Icons.Default.Close, "Remove $name from Ghost Mode", { remove(id) }, enabled = editable, quiet = true)
                }
                PreferenceToggle("Suppress typing · $name", value.typing, editable) { policy(id, value.copy(typing = it)) }
                PreferenceToggle("Suppress recording · $name", value.recording, editable) { policy(id, value.copy(recording = it)) }
                PreferenceToggle("Hide exact Last Seen & Online · $name", value.status, editable) { policy(id, value.copy(status = it)) }
            }
        }
        item {
            DefaultRule()
            Text("Telegram contacts", style = MaterialTheme.typography.titleMedium)
        }
        items(available, key = { "contact:${it.id}" }, contentType = { "available_contact" }) { contact ->
            Row(Modifier.fillMaxWidth().excludeModuleGestures(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TelegramAvatar(contact.name, contact.photo, 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(contact.name, style = MaterialTheme.typography.bodyMedium)
                    contact.username?.let { Text("@$it", style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary) }
                }
                DefaultIconControl(Icons.Default.Add, "Select ${contact.name} for Ghost Mode", { select(contact.id) }, enabled = editable, quiet = true)
            }
        }
        if (available.isEmpty()) item { Text("No matching unselected contacts", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary) }
        item {
            DefaultRule()
            Text("No complete invisibility. Sending or reading messages can briefly expose online activity, including in shared groups. Premium does not bypass explicit exclusions or remove these exceptions.", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
            Text("Local indicator suppression covers direct chats in Default, not other clients or shared-group activity. This build has no outgoing typing/recording broadcasts or recorder. Logging out does not restore server privacy rules.", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
        }
    }
}
