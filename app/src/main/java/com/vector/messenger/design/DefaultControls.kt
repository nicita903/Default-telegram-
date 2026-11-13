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
import androidx.compose.material3.Icon
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

@Composable
fun DefaultIconControl(
    icon: ImageVector, description: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true, emphasized: Boolean = false, quiet: Boolean = false,
) {
    val colors = DefaultDesign.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.92f) else 1f, VectorMotionSpec.scale(), label = "Control compression")
    val fill by animateColorAsState(if (pressed && emphasized) androidx.compose.ui.graphics.Color(0xFF2679DB) else if (pressed) colors.raised else if (emphasized && enabled) colors.accent else if (quiet) androidx.compose.ui.graphics.Color.Transparent else colors.panel, label = "Control state")
    Box(modifier.size(DefaultDesign.minimumTarget).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(RoundedCornerShape(DefaultDesign.controlRadius)).background(fill)
        .border(1.dp, if (focused) colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(DefaultDesign.controlRadius))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, description, Modifier.size(21.dp), tint = if (!enabled) colors.secondary.copy(alpha = .5f) else if (emphasized) colors.base else colors.text)
    }
}

@Composable
fun DefaultAction(
    text: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    icon: ImageVector? = null, emphasized: Boolean = false, enabled: Boolean = true,
) {
    val colors = DefaultDesign.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val focused by interaction.collectIsFocusedAsState()
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.97f) else 1f, VectorMotionSpec.scale(), label = "Action compression")
    Row(modifier.heightIn(min = DefaultDesign.minimumTarget).graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .clip(RoundedCornerShape(DefaultDesign.controlRadius))
        .background(if (emphasized) colors.accent else if (pressed) colors.raised else colors.panel)
        .border(1.dp, if (focused) colors.accent else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(DefaultDesign.controlRadius))
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val foreground = if (emphasized) colors.base else colors.text
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
    val frame by animateColorAsState(if (focused) colors.accent else colors.line, label = "Input focus")
    val shape = RoundedCornerShape(28.dp)
    BasicTextField(
        value, onValueChange, singleLine = singleLine, maxLines = maxLines,
        textStyle = MaterialTheme.typography.bodyLarge.merge(TextStyle(color = colors.text)),
        cursorBrush = SolidColor(colors.accent),
        modifier = modifier.onFocusChanged { focused = it.isFocused }.semantics { contentDescription = placeholder },
        decorationBox = { editor ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).background(surface ?: colors.panel, shape)
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
    Box(modifier.fillMaxWidth().height(1.dp).background(DefaultDesign.colors.line))
}

@Composable
fun DefaultDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val colors = DefaultDesign.colors
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.padding(20.dp).widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 580.dp)
            .clip(RoundedCornerShape(DefaultDesign.panelRadius)).background(colors.panel)
            .border(1.dp, colors.line, RoundedCornerShape(DefaultDesign.panelRadius)).padding(20.dp)
            .semantics { paneTitle = title }, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(title, color = colors.text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}

@Composable
fun DefaultActionMenu(open: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    if (open) Popup(alignment = Alignment.TopEnd, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true)) {
        val colors = DefaultDesign.colors
        Column(Modifier.widthIn(min = 164.dp, max = 240.dp).clip(RoundedCornerShape(DefaultDesign.controlRadius))
            .background(colors.raised).border(1.dp, colors.line, RoundedCornerShape(DefaultDesign.controlRadius)).padding(4.dp)) { content() }
    }
}
