package com.vector.messenger.feature

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
fun ConversationScreen(chatId: Long, viewModel: ChatsViewModel) {
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val messages = remember(allMessages, chatId) { allMessages.filter { it.chatId == chatId } }
    val newestFirst = remember(messages) { messages.asReversed() }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val chat = chats.find { it.id == chatId }
    var draft by rememberSaveable(chatId) { mutableStateOf("") }
    var replyTo by rememberSaveable(chatId) { mutableStateOf<Long?>(null) }
    var attachmentUri by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    var attachmentName by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    var attachmentMime by rememberSaveable(chatId) { mutableStateOf<String?>(null) }
    var fileError by remember { mutableStateOf<String?>(null) }
    var actionsFor by remember { mutableStateOf<Long?>(null) }
    var selectText by remember { mutableStateOf<String?>(null) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    var previousLastId by rememberSaveable(chatId) { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            try {
                val info = withContext(Dispatchers.IO) {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } ?: "Document"
                    name to (context.contentResolver.getType(uri) ?: "application/octet-stream")
                }
                attachmentUri = uri.toString(); attachmentName = info.first; attachmentMime = info.second; fileError = null
            } catch (_: SecurityException) { fileError = "This file is no longer accessible. Choose it again." }
            catch (_: java.io.IOException) { fileError = "Unable to read this file." }
        }
    }
    LaunchedEffect(messages.lastOrNull()?.id) {
        val last = messages.lastOrNull() ?: return@LaunchedEffect
        if (last.id != previousLastId) {
            if (previousLastId == null || last.delivery == Delivery.LocalDemo) listState.scrollToItem(0)
            previousLastId = last.id
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).testTag("message_list"), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp)) {
            itemsIndexed(newestFirst, key = { _, it -> it.id }, contentType = { _, _ -> "message" }) { index, message ->
                val outgoing = message.delivery == Delivery.LocalDemo
                val previous = newestFirst.getOrNull(index + 1)
                val grouped = previous != null && (previous.delivery == Delivery.LocalDemo) == outgoing
                Row(Modifier.fillMaxWidth().padding(top = if (grouped) 3.dp else 12.dp).excludeModuleGestures(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    if (!outgoing && chat?.kind == ChatKind.Group) {
                        ChatAvatar(chat.title, 28.dp)
                        Spacer(Modifier.width(6.dp))
                    }
                    MessageBubble(
                        message, byId[message.replyTo], grouped,
                        onActions = { actionsFor = message.id }, onReply = { replyTo = message.id },
                        onReaction = { viewModel.react(chatId, message.id, it) },
                        onOpenAttachment = { attachment ->
                            try {
                                context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(attachment.uri.toUri(), attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            } catch (_: ActivityNotFoundException) { fileError = "No application can open this file." }
                            catch (_: SecurityException) { fileError = "This file is no longer accessible. Choose it again." }
                        },
                    )
                }
            }
        }
        Column(Modifier.excludeModuleGestures().background(MaterialTheme.colorScheme.background)) {
            byId[replyTo]?.let { reply ->
                ComposerPreview("Reply to ${if (reply.delivery == Delivery.LocalDemo) "you" else chat?.title.orEmpty()}", reply.text.ifEmpty { reply.attachment?.name.orEmpty() }) { replyTo = null }
            }
            attachmentName?.let { name -> ComposerPreview("Local attachment", name) { attachmentUri = null; attachmentName = null; attachmentMime = null } }
            (fileError ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        IconButton(onClick = {
                            try { picker.launch(arrayOf("*/*")) } catch (_: ActivityNotFoundException) { fileError = "No document picker is available." }
                        }) { Icon(VectorIcons.Attach, "Attach file") }
                        TextField(
                            value = draft, onValueChange = { draft = it }, placeholder = { Text("Demo message") }, maxLines = 5,
                            modifier = Modifier.weight(1f),
                            colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface, unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent, unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent),
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                FilledIconButton(enabled = draft.isNotBlank() || attachmentUri != null, shape = CircleShape, modifier = Modifier.size(48.dp), onClick = {
                    val attachment = attachmentUri?.let { Attachment(attachmentName ?: "Document", it, attachmentMime ?: "application/octet-stream") }
                    viewModel.send(chatId, draft, replyTo, attachment) { draft = ""; replyTo = null; attachmentUri = null; attachmentName = null; attachmentMime = null }
                }) { Icon(Icons.AutoMirrored.Filled.Send, "Send demo message") }
            }
        }
    }
    byId[actionsFor]?.let { selected ->
        AlertDialog(onDismissRequest = { actionsFor = null }, title = { Text("Message") },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("👍", "❤", "✨").forEach { reaction -> TextButton(onClick = { viewModel.react(chatId, selected.id, reaction); actionsFor = null }) { Text(reaction) } }
                    }
                    TextButton(onClick = { replyTo = selected.id; actionsFor = null }) { Text("Reply") }
                    TextButton(onClick = { selectText = selected.text; actionsFor = null }) { Text("Select text") }
                }
            }, confirmButton = { TextButton(onClick = { actionsFor = null }) { Text("Done") } })
    }
    selectText?.let { text -> AlertDialog(onDismissRequest = { selectText = null }, title = { Text("Select text") }, text = { SelectionContainer { Text(text) } }, confirmButton = { TextButton(onClick = { selectText = null }) { Text("Done") } }) }
}

@Composable
private fun ComposerPreview(title: String, text: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(36.dp).background(MaterialTheme.colorScheme.primary))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
        }
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Remove $title") }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    message: Message, reply: Message?, grouped: Boolean,
    onActions: () -> Unit, onReply: () -> Unit, onReaction: (String) -> Unit, onOpenAttachment: (Attachment) -> Unit,
) {
    val outgoing = message.delivery == Delivery.LocalDemo
    var drag by remember { mutableFloatStateOf(0f) }
    val translation = animateFloatAsState(drag, VectorMotionSpec.scale(), label = "Swipe to reply")
    val replyDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val currentReply by rememberUpdatedState(onReply)
    Column(Modifier.widthIn(max = 310.dp).graphicsLayer { translationX = translation.value }
        .pointerInput(message.id, replyDistance) {
            detectHorizontalDragGestures(onDragEnd = { if (drag >= replyDistance) currentReply(); drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                change.consume(); drag = max(0f, drag + amount).coerceAtMost(replyDistance * 1.2f)
            }
        }
        .background(if (outgoing) MaterialTheme.colorScheme.primary.copy(alpha = .14f) else MaterialTheme.colorScheme.surfaceContainer,
            RoundedCornerShape(topStart = if (grouped && !outgoing) 4.dp else 16.dp, topEnd = if (grouped && outgoing) 4.dp else 16.dp, bottomEnd = if (outgoing) 4.dp else 16.dp, bottomStart = if (outgoing) 16.dp else 4.dp))
        .combinedClickable(onClick = {}, onLongClick = onActions).padding(horizontal = 12.dp, vertical = 8.dp)) {
        if (message.replyTo != null) {
            Row(Modifier.padding(bottom = 6.dp)) {
                Box(Modifier.width(2.dp).height(32.dp).background(MaterialTheme.colorScheme.primary))
                Column(Modifier.padding(start = 8.dp)) {
                    Text("Reply", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(reply?.text?.ifEmpty { reply.attachment?.name.orEmpty() } ?: "Earlier message", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        message.attachment?.let { attachment -> TextButton(onClick = { onOpenAttachment(attachment) }) { Icon(VectorIcons.Attach, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(attachment.name, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        if (message.text.isNotEmpty()) Text(message.text, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.align(Alignment.End).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(message.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (outgoing) {
                Icon(VectorIcons.Clock, "Stored locally; not sent to Telegram", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Local demo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        message.reaction?.let { reaction -> TextButton(onClick = { onReaction(reaction) }, contentPadding = PaddingValues(horizontal = 8.dp), modifier = Modifier.heightIn(min = 48.dp)) { Text("$reaction  1") } }
    }
}
