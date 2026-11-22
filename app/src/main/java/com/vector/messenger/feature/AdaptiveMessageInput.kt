package com.vector.messenger.feature

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.activity.compose.BackHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
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
    val settings = LocalAppearance.current
    val colors = DefaultDesign.colors
    val showSend = editing || value.text.isNotEmpty() || attachmentName != null
    val showMic = !showSend || recording
    var expanded by remember { mutableStateOf(false) }
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
        Box(Modifier.fillMaxWidth().animateContentSize(VectorMotionSpec.transition(settings))) {
            Row(Modifier.align(Alignment.BottomStart)) {
                DefaultIconControl(Icons.Default.Add, "Attach file", onAttach,
                    enabled = attachmentsEnabled && !editing && !busy && !recording, quiet = true)
                DefaultIconControl(if (emojiOpen) VectorIcons.Keyboard else VectorIcons.Emoji,
                    if (emojiOpen) "Show keyboard" else "Choose emoji", {
                        if (emojiOpen) { emojiOpen = false; focus.requestFocus(); keyboard?.show() }
                        else { emojiOpen = true; keyboard?.hide() }
                    }, enabled = enabled && !busy && !recording, quiet = true)
            }
            BasicTextField(value, onValueChange,
                modifier = Modifier.fillMaxWidth().padding(start = 96.dp, end = 48.dp)
                    .padding(bottom = if (expanded) 48.dp else 0.dp).heightIn(min = 48.dp).testTag("message_composer")
                    .focusRequester(focus).pointerInput(chatId) {
                        awaitEachGesture { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); emojiOpen = false }
                    }
                    .semantics { contentDescription = if (editing) "Edit message" else "Message" },
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.text),
                cursorBrush = SolidColor(colors.accent), singleLine = false, minLines = 1, maxLines = 6,
                readOnly = !enabled || recording || (editing && busy),
                onTextLayout = { layout -> expanded = layout.lineCount > 1 },
                decorationBox = { editor ->
                    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (value.text.isEmpty()) Text(if (contextTitle != null && !editing) "Write a reply…" else "Write a message…",
                            color = colors.secondary, style = MaterialTheme.typography.bodyLarge)
                        editor()
                    }
                })
            Box(Modifier.align(Alignment.BottomEnd).size(48.dp)) {
                // Keep the microphone mounted: text changes must not dispose its recording job.
                Box(Modifier.size(if (showMic) 48.dp else 0.dp).graphicsLayer { alpha = 1f - actionAlpha.value }
                    .then(if (!showMic) Modifier.clearAndSetSemantics {} else Modifier)) {
                    if (live) VoiceMicrophone(chatId, replyTo, enabled && showMic && !busy)
                    else DefaultIconControl(VectorIcons.Microphone, "Voice messages require Telegram", {}, enabled = false, quiet = true)
                }
                if (!showMic) Box(Modifier.size(48.dp).graphicsLayer { alpha = actionAlpha.value }) {
                    DefaultIconControl(if (editing) Icons.Default.Check else Icons.AutoMirrored.Filled.Send,
                        if (editing) "Save message edit" else if (live) "Send message" else "Send demo message", onSubmit,
                        enabled = !showMic && enabled && !busy && !recording && (value.text.isNotBlank() || (!editing && attachmentName != null)),
                        emphasized = true)
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
