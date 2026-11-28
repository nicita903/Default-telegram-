package com.vector.messenger.feature

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vector.messenger.calling.CallIcons
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures

/** Settings shares Default's 24-unit, 1.7-unit line icon geometry. No extra icon dependency. */
internal object SettingsIcons {
    private fun line(name: String, draw: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round, pathBuilder = draw)
    }.build()
    val Palette by lazy { line("Appearance") {
        moveTo(12f, 3f); curveTo(1f, 2f, 0f, 20f, 10f, 21f); curveTo(14f, 22f, 14f, 18f, 12f, 17f)
        curveTo(10f, 15f, 15f, 14f, 18f, 15f); curveTo(25f, 16f, 23f, 3f, 12f, 3f); close()
        moveTo(7f, 9f); lineTo(7.1f, 9f); moveTo(12f, 7f); lineTo(12.1f, 7f); moveTo(17f, 9f); lineTo(17.1f, 9f)
    } }
    val Shield by lazy { line("Privacy") {
        moveTo(12f, 2f); lineTo(21f, 6f); lineTo(20f, 14f); curveTo(19f, 18f, 15f, 21f, 12f, 22f)
        curveTo(9f, 21f, 5f, 18f, 4f, 14f); lineTo(3f, 6f); close(); moveTo(8f, 12f); lineTo(11f, 15f); lineTo(16f, 9f)
    } }
    val Speaker by lazy { line("Sounds") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f); moveTo(19f, 5f); curveTo(24f, 9f, 24f, 15f, 19f, 19f)
    } }
    val Bell by lazy { line("Alerts") {
        moveTo(5f, 16f); lineTo(6f, 13f); lineTo(6f, 9f); curveTo(6f, 1f, 18f, 1f, 18f, 9f)
        lineTo(18f, 13f); lineTo(20f, 17f); lineTo(4f, 17f); close(); moveTo(9f, 21f); quadTo(12f, 23f, 15f, 21f)
    } }
    val User by lazy { line("Profile") {
        moveTo(16f, 7f); curveTo(16f, 13f, 8f, 13f, 8f, 7f); curveTo(8f, 1f, 16f, 1f, 16f, 7f); close()
        moveTo(4f, 21f); curveTo(4f, 12f, 20f, 12f, 20f, 21f)
    } }
    val Group by lazy { line("Groups") {
        moveTo(14f, 7f); curveTo(14f, 12f, 6f, 12f, 6f, 7f); curveTo(6f, 2f, 14f, 2f, 14f, 7f); close()
        moveTo(2f, 21f); curveTo(2f, 12f, 18f, 12f, 18f, 21f); moveTo(18f, 4f); curveTo(23f, 5f, 23f, 10f, 18f, 11f)
        moveTo(19f, 15f); quadTo(23f, 16f, 23f, 21f)
    } }
    val Forward by lazy { line("Forwarded messages") { moveTo(4f, 19f); lineTo(4f, 14f); quadTo(4f, 8f, 10f, 8f); lineTo(21f, 8f); moveTo(16f, 3f); lineTo(21f, 8f); lineTo(16f, 13f) } }
    val Block by lazy { line("Blocked users") { moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f); close(); moveTo(6f, 6f); lineTo(18f, 18f) } }
    val Key by lazy { line("Two-step verification") { moveTo(12f, 8f); curveTo(12f, 16f, 2f, 16f, 2f, 8f); curveTo(2f, 1f, 12f, 1f, 12f, 8f); close(); moveTo(11f, 11f); lineTo(21f, 21f); lineTo(23f, 19f); moveTo(18f, 18f); lineTo(20f, 16f) } }
    val Lock by lazy { line("Passcode lock") { moveTo(6f, 10f); lineTo(18f, 10f); quadTo(20f, 10f, 20f, 12f); lineTo(20f, 20f); lineTo(4f, 20f); lineTo(4f, 12f); quadTo(4f, 10f, 6f, 10f); moveTo(7f, 10f); lineTo(7f, 7f); curveTo(7f, 0f, 17f, 0f, 17f, 7f); lineTo(17f, 10f); moveTo(12f, 14f); lineTo(12f, 17f) } }
    val Devices by lazy { line("Devices") { moveTo(2f, 4f); lineTo(17f, 4f); lineTo(17f, 14f); lineTo(2f, 14f); close(); moveTo(8f, 14f); lineTo(8f, 19f); moveTo(4f, 19f); lineTo(12f, 19f); moveTo(16f, 11f); lineTo(22f, 11f); lineTo(22f, 22f); lineTo(16f, 22f); close() } }
    val Phone by lazy { line("Phone number") { moveTo(6f, 2f); lineTo(18f, 2f); lineTo(18f, 22f); lineTo(6f, 22f); close(); moveTo(10f, 18f); lineTo(14f, 18f) } }
    val Ghost by lazy { line("Ghost Mode") { moveTo(5f, 21f); lineTo(5f, 9f); curveTo(5f, 0f, 19f, 0f, 19f, 9f); lineTo(19f, 21f); lineTo(15f, 18f); lineTo(12f, 21f); lineTo(9f, 18f); close(); moveTo(9f, 9f); lineTo(9f, 11f); moveTo(15f, 9f); lineTo(15f, 11f) } }
    val Storage by lazy { line("Storage") { moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close(); moveTo(3f, 13f); lineTo(21f, 13f); moveTo(16f, 16f); lineTo(18f, 16f) } }
    val Controls by lazy { line("Controls") { moveTo(3f, 6f); lineTo(9f, 6f); moveTo(15f, 6f); lineTo(21f, 6f); moveTo(9f, 3f); lineTo(9f, 9f); moveTo(3f, 17f); lineTo(15f, 17f); moveTo(21f, 17f); lineTo(22f, 17f); moveTo(15f, 14f); lineTo(15f, 20f) } }
    fun route(route: String): ImageVector = when (route) {
        "appearance" -> Palette; "privacy" -> Shield; "sounds" -> Speaker; "notifications" -> Bell
        "devices" -> Devices; "storage", "tg_cache" -> Storage; "music" -> VectorIcons.Music
        "messaging" -> VectorIcons.Chat; "calling", "tg_privacy_calls", "tg_privacy_p2p" -> CallIcons.Phone
        "tg_blocked" -> Block; "tg_privacy_seen" -> VectorIcons.Clock; "tg_privacy_phone" -> Phone
        "tg_privacy_photos" -> User; "tg_privacy_invites" -> Group; "tg_privacy_forward" -> Forward
        "tg_password" -> Key; "tg_passcode" -> Lock; "tg_ghost" -> Ghost; "tg_downloads" -> VectorIcons.Download
        "tg_notifications_private" -> VectorIcons.Chat; "tg_notifications_groups", "tg_notifications_channels" -> Group
        else -> Controls
    }
}

@Composable internal fun SettingsModuleTile(title: String, icon: ImageVector, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.98f, appearance) else 1f, VectorMotionSpec.transition(appearance), label = "Settings tile press")
    val material = colors.panel.copy(alpha = if (appearance.interfaceStyle == InterfaceStyle.AuroraGlass && !appearance.amoledBlack) .72f else 1f)
    val fill by animateColorAsState(if (pressed) colors.raised else material, VectorMotionSpec.transition(appearance), label = "Settings tile tone")
    val haptic = LocalHapticFeedback.current
    Column(modifier.heightIn(min = 110.dp).excludeModuleGestures().graphicsLayer { scaleX = scale; scaleY = scale }
        .clip(RoundedCornerShape(20.dp)).background(fill)
        .clickable(interactionSource = interaction, indication = null, role = Role.Button) {
            if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }.padding(horizontal = 12.dp, vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
        DesignIcon(icon, null, Modifier.size(30.dp), tint = if (accent) colors.accent else colors.text.copy(alpha = .86f))
        Text(title, color = colors.text, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
    }
}

@Composable internal fun SettingsRadioRow(title: String, selected: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = DefaultDesign.colors
    val haptic = LocalHapticFeedback.current
    val appearance = LocalAppearance.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).excludeModuleGestures().clip(RoundedCornerShape(14.dp))
        .background(if (selected) colors.accent.copy(alpha = .07f) else Color.Transparent)
        .selectable(selected, enabled = enabled, role = Role.RadioButton) {
            if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onClick()
        }.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.size(20.dp)) {
            drawCircle(if (selected) colors.accent else colors.secondary, style = androidx.compose.ui.graphics.drawscope.Stroke(1.6.dp.toPx()))
            if (selected) drawCircle(colors.accent, radius = 5.dp.toPx())
        }
        Text(title, style = MaterialTheme.typography.bodyMedium, color = if (enabled) colors.text else colors.secondary)
    }
}

@Composable internal fun SettingsExplanation(title: String = "About this setting", text: String) {
    var open by remember { mutableStateOf(false) }
    CompactSettingsRow(title, icon = SettingsIcons.Controls, chevron = false) { open = !open }
    if (open) SettingsNote(text)
}
