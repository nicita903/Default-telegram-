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
fun SettingsScreen(viewModel: PreferencesViewModel, onPrivacy: () -> Unit) {
    val settings by viewModel.appearance.collectAsStateWithLifecycle()
    val sounds by viewModel.sounds.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp).testTag("settings_scroll"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.vector.messenger.feature.avatar.AccountSummary()
        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Text("Background", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().excludeModuleGestures(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DefaultAction("Standard Dark", { viewModel.appearance { it.copy(amoledBlack = false) } }, Modifier.weight(1f), emphasized = !settings.amoledBlack)
            DefaultAction("AMOLED Black", { viewModel.appearance { it.copy(amoledBlack = true) } }, Modifier.weight(1f), emphasized = settings.amoledBlack)
        }
        Text("Aurora keeps one dark canvas. AMOLED replaces the base with pure black and leaves the colour field in place.",
            style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
        Text("Surfaces", style = MaterialTheme.typography.titleMedium)
        BlurIntensitySetting(settings.glassBlur) { value -> viewModel.appearance { it.copy(glassBlur = value) } }
        Text("Glass surfaces are always on. Blur intensity applies to the covered screen behind the module menu and to dialog windows; it needs Android 12 or newer.",
            style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
        Text("Module navigation", style = MaterialTheme.typography.titleMedium)
        PreferenceToggle("Swipe and hold navigation", settings.modules, viewModel::moduleGestures)
        SettingStepper("Swipe activation distance", settings.distance, listOf(20, 28, 36, 48, 60), "dp") { value -> viewModel.appearance { it.copy(distance = value) } }
        SettingStepper("Hold duration", settings.hold, listOf(120, 180, 240, 320, 400), "ms") { value -> viewModel.appearance { it.copy(hold = value) } }
        PreferenceToggle("Haptic feedback", settings.haptics) { value -> viewModel.appearance { it.copy(haptics = value) } }
        SettingStepper("Screen zoom intensity", settings.zoom, listOf(0, 3, 5, 8, 10), "%") { value -> viewModel.appearance { it.copy(zoom = value) } }
        DefaultRule()
        Text("Search navigation", style = MaterialTheme.typography.titleMedium)
        PreferenceToggle("Pull-down search", settings.pullSearch) { value -> viewModel.appearance { it.copy(pullSearch = value) } }
        SettingStepper("Pull-down activation distance", settings.pullDistance, listOf(48, 60, 72, 96, 120), "dp") { value -> viewModel.appearance { it.copy(pullDistance = value) } }
        PreferenceToggle("Swipe-down dismissal", settings.dismissSearch) { value -> viewModel.appearance { it.copy(dismissSearch = value) } }
        PreferenceToggle("Fast Chat/Music switching", settings.fastSearch) { value -> viewModel.appearance { it.copy(fastSearch = value) } }
        SettingStepper("Fast-swipe sensitivity", settings.sensitivity, listOf(70, 85, 100, 120, 140), "%") { value -> viewModel.appearance { it.copy(sensitivity = value) } }
        PreferenceToggle("Automatic search locking", settings.autoLock) { value -> viewModel.appearance { it.copy(autoLock = value) } }
        PreferenceToggle("Lock indicator visibility", settings.lockVisible) { value -> viewModel.appearance { it.copy(lockVisible = value) } }
        PreferenceToggle("Lock feedback", settings.elastic) { value -> viewModel.appearance { it.copy(elastic = value) } }
        DefaultRule()
        Text("Animations", style = MaterialTheme.typography.titleMedium)
        SettingStepper("Animation speed", settings.speed, listOf(50, 75, 100, 125, 150), "%") { value -> viewModel.appearance { it.copy(speed = value) } }
        SettingStepper("Animation intensity", settings.intensity, listOf(0, 25, 50, 75, 100), "%") { value -> viewModel.appearance { it.copy(intensity = value) } }
        PreferenceToggle("Reduced motion", settings.reducedMotion) { value -> viewModel.appearance { it.copy(reducedMotion = value) } }
        DefaultRule()
        Text("Interface", style = MaterialTheme.typography.titleMedium)
        SettingStepper("Density", if (settings.compact) 0 else 1, listOf(0, 1), "", listOf("Compact", "Comfortable")) { value -> viewModel.appearance { it.copy(compact = value == 0) } }
        SettingStepper("Accent color", settings.accent, listOf(0, 1, 2), "", listOf("Aurora blue", "Violet", "Teal")) { value -> viewModel.appearance { it.copy(accent = value) } }
        SettingStepper("Typography scale", settings.typeScale, listOf(85, 100, 115, 130), "%") { value -> viewModel.appearance { it.copy(typeScale = value) } }
        Text("Pull from the top of Chats to search. Fast swipes change an unlocked search scope; swipe and hold opens modules after release. Search is also available in the module menu.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        DefaultRule()
        Text("Privacy", style = MaterialTheme.typography.titleMedium)
        DefaultAction("Ghost Mode", onPrivacy)
        DefaultRule()
        com.vector.messenger.notifications.NotificationSettings(viewModel)
        DefaultRule()
        Text("Sounds", style = MaterialTheme.typography.titleMedium)
        PreferenceToggle("Message sounds", sounds.messages) { value -> viewModel.soundPreference { it.copy(messages = value) } }
        PreferenceToggle("Send sound", sounds.send) { value -> viewModel.soundPreference { it.copy(send = value) } }
        PreferenceToggle("Receive sound", sounds.receive) { value -> viewModel.soundPreference { it.copy(receive = value) } }
        PreferenceToggle("Failed message sound", sounds.errors) { value -> viewModel.soundPreference { it.copy(errors = value) } }
        PreferenceToggle("Interface sounds", sounds.interfaceSounds) { value -> viewModel.soundPreference { it.copy(interfaceSounds = value) } }
        SettingStepper("Sound volume", sounds.volume, (0..100 step 5).toList(), "%") { value -> viewModel.soundPreference { it.copy(volume = value) } }
        Text("In-app sounds stay quiet in silent mode, Do Not Disturb, calls, recording and music playback. Notification volume is controlled by Android.", style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
        val soundContext = androidx.compose.ui.platform.LocalContext.current
        listOf("Messages" to "messages_v1", "Groups" to "groups_v1", "Mentions" to "mentions_v1").forEach { (name, channel) ->
            DefaultAction("$name notification sound", {
                soundContext.startActivity(android.content.Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, soundContext.packageName).putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, channel))
            })
        }
        HorizontalDivider()
        Text("About DEFAULT", style = MaterialTheme.typography.titleMedium)
        Text("Default · 0.1.0\nAppearance preferences are stored on this device. Signed-in chats use Telegram; signed-out chats are a separate demonstration. Manage your session from Account.")
        Text("Telegram cloud chats are not end-to-end encrypted. Only supported Secret Chats offer Telegram's end-to-end encryption model.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only publish a new discrete radius, not every fractional thumb position. */
@Composable
private fun BlurIntensitySetting(value: Int, onChange: (Int) -> Unit) {
    var position by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().excludeModuleGestures(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Blur Intensity", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(listOf("Off", "Low", "Medium", "High")[value.coerceIn(0, 3)],
                color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        }
        Slider(value = position, onValueChange = { next ->
            position = next
            val level = next.roundToInt().coerceIn(0, 3)
            if (level != value) onChange(level)
        }, valueRange = 0f..3f, steps = 2, modifier = Modifier.fillMaxWidth())
    }
}


@Composable
fun PrivacyScreen(contacts: List<Chat>, viewModel: PreferencesViewModel) {
    val liveState by viewModel.ghostState.collectAsStateWithLifecycle()
    if (liveState.authenticated) {
        SelectiveGhostScreen(liveState, viewModel::ghostEnabled, viewModel::selectGhost, viewModel::removeGhost,
            viewModel::ghostPolicy, viewModel::refreshGhost, viewModel::applyGhostPrivacy, viewModel::keepCurrentPrivacy)
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
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).excludeModuleGestures().toggleable(value = selected, enabled = enabled, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Box(Modifier.width(30.dp).height(18.dp).background(if (selected) DefaultDesign.colors.accent else DefaultDesign.colors.raised, DefaultDesign.controlShape).padding(3.dp)) {
            Box(Modifier.size(12.dp).align(if (selected) Alignment.CenterEnd else Alignment.CenterStart).background(if (selected) DefaultDesign.colors.onAccent else DefaultDesign.colors.secondary, DefaultDesign.avatarShape))
        }
    }
}

@Composable
private fun SettingStepper(title: String, value: Int, options: List<Int>, unit: String, names: List<String>? = null, onChange: (Int) -> Unit) {
    val index = options.indexOf(value).coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).excludeModuleGestures(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
            Text(names?.getOrNull(index) ?: "$value $unit", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        }
        DefaultIconControl(VectorIcons.Minus, "Decrease $title", { onChange(options[(index - 1).coerceAtLeast(0)]) }, enabled = index > 0, quiet = true)
        DefaultIconControl(Icons.Default.Add, "Increase $title", { onChange(options[(index + 1).coerceAtMost(options.lastIndex)]) }, enabled = index < options.lastIndex, quiet = true)
    }
}
