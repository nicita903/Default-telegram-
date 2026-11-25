package com.vector.messenger.feature

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainHeight
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.feature.audio.VoiceMicrophone
import com.vector.messenger.feature.audio.VoiceRecordingPanel
import com.vector.messenger.navigation.gesture.excludeModuleGestures

/** One editor stays at the same composition position through every composer state. */
@Composable
fun AdaptiveMessageInput(
    chatId: Long, replyTo: Long?, value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit,
    live: Boolean, enabled: Boolean, busy: Boolean, recording: Boolean, editing: Boolean,
    contextTitle: String?, contextText: String, onCancelContext: () -> Unit,
    attachmentName: String?, onRemoveAttachment: () -> Unit, error: String?,
    onAttach: () -> Unit, onSubmit: () -> Unit,
    attachmentsEnabled: Boolean = enabled,
) {
    val video = com.vector.messenger.feature.video.LocalVideoNotes.current
    val videoMode by (video?.videoMode ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsStateWithLifecycle()
    val settings = LocalAppearance.current
    val colors = DefaultDesign.colors
    val showSend = editing || value.text.isNotEmpty() || attachmentName != null
    val showMic = !showSend || recording
    var emojiOpen by rememberSaveable(chatId) { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val latestValue by rememberUpdatedState(value)
    val updateValue by rememberUpdatedState(onValueChange)
    BackHandler(emojiOpen) { emojiOpen = false }
    LaunchedEffect(recording) { if (recording) emojiOpen = false }
    val actionAlpha = animateFloatAsState(if (showMic) 0f else 1f,
        VectorMotionSpec.transition(settings), label = "Composer action")
    Column(Modifier.fillMaxWidth().excludeModuleGestures().padding(horizontal = 8.dp, vertical = 6.dp)) {
        // Never put recording UI in an animated/disposable branch of the text editor.
        VoiceRecordingPanel(chatId)
        com.vector.messenger.feature.video.VideoNoteRecorderPanel(chatId)
        AnimatedContent(contextTitle?.let { it to contextText }, transitionSpec = {
            (VectorMotionSpec.lockEnter(settings) togetherWith VectorMotionSpec.lockExit(settings))
                .using(SizeTransform { _, _ -> VectorMotionSpec.transition(settings) })
        }, label = "Composer context") { context ->
            if (context != null) InputContext(context.first, context.second, onCancelContext, !busy)
        }
        AnimatedVisibility(attachmentName != null && !editing,
            enter = VectorMotionSpec.lockEnter(settings), exit = VectorMotionSpec.lockExit(settings)) {
            InputContext("Local attachment", attachmentName.orEmpty(), onRemoveAttachment, !busy)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text)
            val measurer = rememberTextMeasurer(cacheSize = 4)
            // Decide against the compact width even while expanded to avoid wrapping feedback.
            val compactWidth = with(density) { (maxWidth - 148.dp).roundToPx().coerceAtLeast(1) }
            val expandedWidth = with(density) { (maxWidth - 24.dp).roundToPx().coerceAtLeast(1) }
            val compactText = remember(value.text, compactWidth, textStyle, measurer) {
                measurer.measure(AnnotatedString(value.text), textStyle,
                    constraints = Constraints(maxWidth = compactWidth), maxLines = 3)
            }
            val expandedText = remember(value.text, expandedWidth, textStyle, measurer) {
                measurer.measure(AnnotatedString(value.text), textStyle,
                    constraints = Constraints(maxWidth = expandedWidth), maxLines = 6)
            }
            val needsExpansion = compactText.lineCount >= 3
            var expanded by remember(chatId) { mutableStateOf(needsExpansion) }
            LaunchedEffect(needsExpansion, chatId) {
                if (!needsExpansion) delay(250)
                expanded = needsExpansion
            }
            val morph = animateFloatAsState(if (expanded) 1f else 0f,
                tween(VectorMotionSpec.duration(240, settings), easing = FastOutSlowInEasing), label = "Composer morph")
            val surface = if (settings.amoledBlack) colors.panel else colors.panel.copy(alpha = .88f)
            Layout(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(surface), content = {
                // The editor and actions retain their composition identities throughout the morph.
                BasicTextField(value, onValueChange,
                    modifier = Modifier.testTag("message_composer").focusRequester(focus).pointerInput(chatId) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); emojiOpen = false }
                    }.semantics { contentDescription = if (editing) "Edit message" else "Message" },
                    textStyle = textStyle, cursorBrush = SolidColor(colors.accent), singleLine = false, minLines = 1, maxLines = 6,
                    readOnly = !enabled || recording || (editing && busy),
                    decorationBox = { editor ->
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                            if (value.text.isEmpty()) Text(if (contextTitle != null && !editing) "Write a reply…" else "Write a message…",
                                color = colors.secondary, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Clip)
                            editor()
                        }
                    })
                ComposerControl(VectorIcons.Plus, "Attach file", onAttach,
                    enabled = attachmentsEnabled && !editing && !busy && !recording, inset = { 3.dp * (1f - morph.value) })
                ComposerControl(if (emojiOpen) VectorIcons.Keyboard else VectorIcons.Emoji,
                    if (emojiOpen) "Show keyboard" else "Choose emoji", {
                        if (emojiOpen) { emojiOpen = false; focus.requestFocus(); keyboard?.show() }
                        else { emojiOpen = true; keyboard?.hide() }
                    }, enabled = enabled && !busy && !recording, inset = { 3.dp * (1f - morph.value) })
                Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    // Do not dispose recording jobs when the send action takes over.
                    Box(Modifier.size(if (showMic) 48.dp else 0.dp).graphicsLayer { alpha = 1f - actionAlpha.value }
                        .then(if (!showMic) Modifier.clearAndSetSemantics {} else Modifier)) {
                        if (live && videoMode) com.vector.messenger.feature.video.VideoNoteButton(chatId, replyTo, enabled && showMic && !busy, composerStyle = true)
                        else if (live) VoiceMicrophone(chatId, replyTo, enabled && showMic && !busy,
                            onTap = { if (!recording) video?.mode(true) }, composerStyle = true)
                        else ComposerControl(VectorIcons.Microphone, "Voice messages require Telegram", {}, enabled = false)
                    }
                    if (!showMic) Box(Modifier.fillMaxSize().graphicsLayer { alpha = actionAlpha.value }) {
                        ComposerControl(if (editing) VectorIcons.Check else VectorIcons.Send,
                            if (editing) "Save message edit" else if (live) "Send message" else "Send demo message", onSubmit,
                            enabled = enabled && !busy && !recording && (value.text.isNotBlank() || (!editing && attachmentName != null)), emphasized = true)
                    }
                }
            }) { children, constraints ->
                val p = morph.value
                fun blend(from: Int, to: Int) = (from + (to - from) * p).roundToInt()
                val width = constraints.maxWidth
                val action = 48.dp.roundToPx()
                val edge = 4.dp.roundToPx()
                val gap = 6.dp.roundToPx()
                val compactButton = 42.dp.roundToPx()
                val pill = ((width - edge * 2 - action - gap * 2) / 2).coerceAtLeast(1)
                val left = blend(92.dp.roundToPx(), 12.dp.roundToPx())
                val right = blend(56.dp.roundToPx(), 12.dp.roundToPx())
                // The third line triggers the morph instead of first jumping the compact height.
                val compactTextHeight = compactText.getLineBottom(minOf(compactText.lineCount, 2) - 1).roundToInt()
                val compactHeight = maxOf(52.dp.roundToPx(), compactTextHeight + 24.dp.roundToPx())
                val expandedHeight = maxOf(52.dp.roundToPx(), expandedText.size.height + 24.dp.roundToPx()) + action
                val height = constraints.constrainHeight(blend(compactHeight, expandedHeight))
                val editorHeight = (height - (action * p).roundToInt()).coerceAtLeast(1)
                val editor = children[0].measure(Constraints.fixed((width - left - right).coerceAtLeast(1), editorHeight))
                val buttonWidth = blend(compactButton, pill)
                val attach = children[1].measure(Constraints.fixed(buttonWidth, action))
                val emoji = children[2].measure(Constraints.fixed(buttonWidth, action))
                val send = children[3].measure(Constraints.fixed(action, action))
                val y = blend((height - action) / 2, height - action - 2.dp.roundToPx())
                layout(width, height) {
                    editor.placeRelative(left, 0)
                    attach.placeRelative(edge, y)
                    emoji.placeRelative(blend(edge + compactButton, edge + pill + gap), y)
                    send.placeRelative(width - edge - action, y)
                }
            }
        }
        AnimatedVisibility(emojiOpen && !imeVisible, enter = VectorMotionSpec.lockEnter(settings), exit = VectorMotionSpec.lockExit(settings)) {
            ConversationEmojiPicker { emoji ->
                if (enabled && !recording && (!editing || !busy)) updateValue(insertEmoji(latestValue, emoji))
            }
        }
    }
}

@Composable
private fun InputContext(title: String, preview: String, onCancel: () -> Unit, enabled: Boolean) {
    val colors = DefaultDesign.colors
    Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = colors.accent, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (preview.isNotEmpty()) Text(preview, color = colors.secondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DefaultIconControl(Icons.Default.Close, "Cancel $title", onCancel, enabled = enabled, quiet = true)
    }
}

/** A 36dp material surface inside a taller hit area; the layout supplies the pill width. */
@Composable
private fun ComposerControl(
    icon: ImageVector, description: String, onClick: () -> Unit, enabled: Boolean,
    emphasized: Boolean = false, inset: () -> androidx.compose.ui.unit.Dp = { 6.dp },
) {
    val colors = DefaultDesign.colors
    val settings = LocalAppearance.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.96f, settings) else 1f,
        VectorMotionSpec.press(pressed, settings), label = "Composer press")
    val fill = animateColorAsState(when {
        emphasized && enabled -> if (pressed) colors.pressedAccent else colors.accent
        pressed -> colors.text.copy(alpha = .14f)
        else -> colors.text.copy(alpha = if (settings.amoledBlack) .09f else .065f)
    }, VectorMotionSpec.feedback(settings), label = "Composer surface")
    Box(Modifier.fillMaxSize().clickable(interactionSource = interaction, indication = null,
        enabled = enabled, role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().padding(horizontal = inset(), vertical = 6.dp)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .clip(RoundedCornerShape(50)).background(fill.value), contentAlignment = Alignment.Center) {
            DesignIcon(icon, description, Modifier.size(20.dp), tint = when {
                !enabled -> colors.secondary.copy(alpha = .5f)
                emphasized -> colors.onAccent
                else -> colors.text
            })
        }
    }
}
