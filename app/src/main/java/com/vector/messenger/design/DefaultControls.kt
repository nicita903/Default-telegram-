package com.vector.messenger.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import com.vector.messenger.design.DesignIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import com.vector.messenger.feature.LocalAppearance

@Composable
fun DefaultIconControl(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, emphasized: Boolean = false, quiet: Boolean = false,
) {
    val colors = DefaultDesign.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val settings = LocalAppearance.current
    val ink = colors.accent
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(VectorMotionSpec.ControlPress) else 1f, VectorMotionSpec.press(pressed, settings), label = "Control compression")
    val fill = animateColorAsState(if (pressed) colors.raised else if (emphasized && enabled) colors.accent else if (quiet) Color.Transparent else colors.panel.copy(alpha = .88f), VectorMotionSpec.feedback(settings), label = "Control state")
    Box(modifier.size(DefaultDesign.minimumTarget).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(DefaultDesign.controlShape)
        // Keep quiet list-row controls on their original paint path. The measured
        // deferred-color benefit is retained for normal filled controls.
        .then(if (quiet) Modifier.background(fill.value) else Modifier.drawBehind { drawRect(fill.value) })
        .border(1.dp, if (focused) ink else Color.Transparent, DefaultDesign.controlShape)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(21.dp), tint = if (!enabled) colors.secondary.copy(alpha = .5f) else if (emphasized) colors.onAccent else colors.text)
    }
}

@Composable
fun DefaultAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, emphasized: Boolean = false, enabled: Boolean = true, compact: Boolean = false,
    destructive: Boolean = false,
) {
    val colors = DefaultDesign.colors
    val floating = LocalFloatingContent.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(VectorMotionSpec.ActionPress) else 1f, VectorMotionSpec.press(pressed, LocalAppearance.current), label = "Action compression")
    Row(modifier.heightIn(min = if (compact) 35.dp else DefaultDesign.minimumTarget).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(DefaultDesign.controlShape)
        .background(
            when {
                floating -> if (pressed) colors.raised.copy(alpha = .6f) else Color.Transparent
                emphasized && pressed -> colors.pressedAccent
                emphasized -> colors.accent
                pressed -> colors.raised
                else -> colors.panel
            }
        )
        .then(if (emphasized && !floating) Modifier.glassHighlight() else Modifier)
        .border(1.dp, if (focused) colors.accent else Color.Transparent, DefaultDesign.controlShape)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = if (compact) 15.dp else 18.dp,vertical = if (compact) 3.dp else 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (floating) Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally) else Arrangement.spacedBy(8.dp)) {
        val foreground = if (destructive) MaterialTheme.colorScheme.error else if (floating && emphasized) colors.accent else if (emphasized) colors.onAccent else colors.text
        icon?.let { Icon(it, null, Modifier.size(18.dp), tint = foreground) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) foreground else colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun DefaultInput(
    value: String, onValueChange: (String) -> Unit, placeholder: String,
    modifier: Modifier = Modifier, leadingIcon: ImageVector? = null,
    singleLine: Boolean = true, maxLines: Int = 1, framed: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    surface: androidx.compose.ui.graphics.Color? = null,
) {
    val colors = DefaultDesign.colors
    var focused by remember { mutableStateOf(false) }
    val frame by animateColorAsState(if (focused) colors.accent else colors.line, VectorMotionSpec.feedback(LocalAppearance.current), label = "Input focus")
    val shape = DefaultDesign.inputShape
    BasicTextField(
        value, onValueChange, singleLine = singleLine, maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = colors.text)),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
        decorationBox = { editor ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(surface ?: colors.panel.copy(alpha = .9f), shape)
                .then(if (framed && focused) Modifier.border(1.dp, frame, shape) else Modifier), verticalAlignment = Alignment.CenterVertically) {
                leadingIcon?.let { Icon(it, null, Modifier.padding(start = 14.dp).size(18.dp), tint = colors.secondary) }
                Box(Modifier.weight(1f).padding(horizontal = 14.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) Text(placeholder, color = colors.secondary, style = MaterialTheme.typography.bodyLarge)
                    editor()
                }
                trailing?.invoke()
            }
        },
    )
}

@Composable
fun DefaultRule(modifier: Modifier = Modifier) {
    val colors = DefaultDesign.colors
    Box(modifier.fillMaxWidth().height(.5.dp).drawBehind {
        drawLine(colors.line, androidx.compose.ui.geometry.Offset(0f, size.height / 2),
            androidx.compose.ui.geometry.Offset(size.width, size.height / 2), 1.dp.toPx())
    })
}

@Composable
fun DefaultDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DefaultFloatingDialog(title, onDismiss, content)
}

@Composable
fun DefaultActionMenu(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val settings = LocalAppearance.current
    val visible = remember { MutableTransitionState(false) }.apply { targetState = open }
    if (visible.currentState || visible.targetState) Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        RegisterFloatingBackdrop()
        AnimatedVisibility(visibleState = visible, enter = VectorMotionSpec.contextEnter(settings), exit = VectorMotionSpec.contextExit(settings)) {
        CompositionLocalProvider(LocalFloatingContent provides true) {
        Box(Modifier.widthIn(min = 164.dp, max = 240.dp)) {
            Column(Modifier.padding(4.dp)) { content() }
        }
        }
        }
    }
}
