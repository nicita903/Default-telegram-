package com.vector.messenger.feature

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures

internal val LocalSettingsHighlight = staticCompositionLocalOf { "" }

@Composable
internal fun CompactSettingsRow(title: String, value: String = "", icon: ImageVector = SettingsIcons.Controls,
    subtitle: String? = null, checked: Boolean? = null, enabled: Boolean = true, chevron: Boolean = checked == null,
    selected: Boolean = false, highlighted: Boolean = LocalSettingsHighlight.current == title,
    leading: (@Composable () -> Unit)? = null, subtitleLines: Int = 1, onClick: () -> Unit) {
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    val haptic = LocalHapticFeedback.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(VectorMotionSpec.RowPress, appearance) else 1f,
        VectorMotionSpec.press(pressed, appearance), label = "Setting press")
    val fill by animateColorAsState(if (pressed || highlighted) colors.accent.copy(alpha = .08f) else Color.Transparent,
        VectorMotionSpec.feedback(appearance), label = "Setting focus")
    val click = { if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
    val action = if (checked != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch,
        interactionSource = interaction, indication = null, onValueChange = { click() })
        else Modifier.clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = click)
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).excludeModuleGestures()
        .graphicsLayer { scaleX = scale; scaleY = scale }.clip(RoundedCornerShape(14.dp)).background(fill)
        .then(action).semantics(mergeDescendants = true) { if (value.isNotEmpty()) stateDescription = value else if (selected) stateDescription = "Selected" }
        .padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (leading != null) leading() else DesignIcon(icon, null, Modifier.size(20.dp), tint = if (selected) colors.accent else colors.secondary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = if (enabled) colors.text else colors.secondary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(subtitle, color = colors.secondary, style = MaterialTheme.typography.labelSmall, maxLines = subtitleLines, overflow = TextOverflow.Ellipsis)
        }
        if (value.isNotEmpty()) Text(value, Modifier.widthIn(max = 120.dp), color = if (checked == true || selected) colors.accent else colors.secondary,
            style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (checked != null) {
            val fraction by animateFloatAsState(if (checked) 1f else 0f, VectorMotionSpec.transition(appearance), label = "Setting toggle")
            Canvas(Modifier.size(32.dp, 20.dp)) {
                drawRoundRect(if (checked) colors.accent.copy(alpha = .23f) else colors.secondary.copy(alpha = .15f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2))
                drawCircle(if (checked) colors.accent else colors.secondary, radius = 6.dp.toPx(),
                    center = androidx.compose.ui.geometry.Offset(10.dp.toPx() + 12.dp.toPx() * fraction, size.height / 2))
            }
        } else if (selected) DesignIcon(VectorIcons.Check, null, Modifier.size(18.dp), tint = colors.accent)
        else if (chevron) DesignIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = colors.secondary)
    }
}

@Composable
internal fun SettingsHeading(title: String) {
    Text(title.uppercase(), Modifier.padding(top = 22.dp, bottom = 8.dp).semantics { heading() },
        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun SettingsSegments(title: String, options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    Column(Modifier.fillMaxWidth().excludeModuleGestures().padding(vertical = 8.dp)) {
        if (title.isNotEmpty()) Text(title, color = colors.text, style = MaterialTheme.typography.bodyMedium)
        BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        val cell = maxWidth / options.size.coerceAtLeast(1)
        val position by androidx.compose.animation.core.animateDpAsState(cell * selected.coerceAtLeast(0),
            androidx.compose.animation.core.tween(VectorMotionSpec.duration(200, appearance)), label = "Setting selection position")
        Box(Modifier.matchParentSize()) {
            Box(Modifier.offset(x = position).width(cell).fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(colors.accent.copy(alpha = .13f)))
        }
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            options.forEachIndexed { index, label ->
                val haptic = LocalHapticFeedback.current
                val enabledHaptic = LocalAppearance.current.haptics
                Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(14.dp))
                    .selectable(index == selected, role = Role.RadioButton) {
                        if (enabledHaptic) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect(index)
                    }.padding(horizontal = 8.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(label, color = if (index == selected) colors.accent else colors.secondary,
                        style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        }
    }
}

@Composable
internal fun SettingsValueSlider(title: String, value: String, selected: Int, options: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().excludeModuleGestures().padding(vertical = 8.dp)
        .background(if (LocalSettingsHighlight.current == title) DefaultDesign.colors.accent.copy(alpha = .08f) else Color.Transparent)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, Modifier.weight(1f), color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
            Text(value, color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = selected.coerceAtLeast(0).toFloat(), onValueChange = { next ->
            val index = next.roundToInt().coerceIn(0, options - 1)
            if (index != selected) onSelect(index)
        }, valueRange = 0f..(options - 1).coerceAtLeast(1).toFloat(), steps = (options - 2).coerceAtLeast(0),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title; stateDescription = value },
            colors = SliderDefaults.colors(thumbColor = DefaultDesign.colors.accent, activeTrackColor = DefaultDesign.colors.accent,
                inactiveTrackColor = DefaultDesign.colors.secondary.copy(alpha = .18f)))
    }
}

@Composable
internal fun SettingsColorSelector(selected: Int, onSelect: (Int) -> Unit) {
    val names = listOf("Blue", "Violet", "Mint")
    val colors = listOf(Color(0xFF7C8CFF), Color(0xFFA78BFA), Color(0xFF48E3C0))
    Column(Modifier.fillMaxWidth().excludeModuleGestures().padding(vertical = 8.dp)) {
        Text("Accent color", color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            colors.forEachIndexed { index, color ->
                Column(Modifier.weight(1f).heightIn(min = 64.dp).selectable(index == selected, role = Role.RadioButton) { onSelect(index) }
                    .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(22.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
                        if (index == selected) DesignIcon(VectorIcons.Check, null, Modifier.size(14.dp), tint = Color.Black)
                    }
                    Text(names[index], color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
internal fun SettingsAccountRow(onProfile: () -> Unit) {
    val avatars = com.vector.messenger.feature.avatar.LocalAvatarViewModel.current ?: return
    val state by avatars.ownProfile.collectAsStateWithLifecycle()
    val profile = state.profile?.takeIf { state.authenticated }
    val account = com.vector.messenger.feature.avatar.LocalAccountAction.current
    Box(Modifier.padding(vertical = 10.dp)) {
        if (profile == null) CompactSettingsRow("My Profile", "Sign in", icon = SettingsIcons.User, onClick = account)
        else CompactSettingsRow(profile.displayName, subtitle = profile.username?.let { "@$it" } ?: "My Profile",
            leading = { com.vector.messenger.feature.avatar.OwnAccountAvatar(52.dp) }, onClick = onProfile)
    }
}

@Composable
internal fun SettingsStoragePanel(viewModel: PreferencesViewModel) {
    val state by viewModel.storage.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { viewModel.refreshStorage() }
    fun size(value: Long?) = value?.let { android.text.format.Formatter.formatFileSize(context, it) } ?: if (state.loading) "Measuring…" else "Unavailable"
    Column(Modifier.fillMaxWidth()) {
        SettingsHeading("Telegram storage")
        val media = state.telegramBytes
        val database = state.databaseBytes
        if (media != null && database != null) {
            Text(size(media + database), color = DefaultDesign.colors.text, style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
            val total = media + database
            Row(Modifier.fillMaxWidth().height(6.dp).clip(CircleShape).background(DefaultDesign.colors.raised)
                .semantics { contentDescription = "Telegram storage usage"; stateDescription = "Media ${size(media)}, database ${size(database)}" }) {
                if (total > 0) {
                    if (media > 0) Box(Modifier.weight((media.toDouble() / total).toFloat().coerceAtLeast(.001f)).fillMaxHeight().background(DefaultDesign.colors.accent))
                    if (database > 0) Box(Modifier.weight((database.toDouble() / total).toFloat().coerceAtLeast(.001f)).fillMaxHeight().background(DefaultDesign.colors.secondary.copy(alpha = .45f)))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        listOf("App disk cache" to state.cacheBytes, "Telegram media" to state.telegramBytes, "Telegram database" to state.databaseBytes).forEach { (label, bytes) ->
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(if (LocalSettingsHighlight.current == label) DefaultDesign.colors.accent.copy(alpha = .08f) else Color.Transparent),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(label, Modifier.weight(1f), color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
                Text(size(bytes), color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelMedium)
            }
        }
        state.error?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall) }
        CompactSettingsRow("Refresh storage usage", icon = VectorIcons.Refresh, chevron = false, enabled = !state.loading) { viewModel.refreshStorage() }
    }
}
