package com.vector.messenger.feature

import android.content.ActivityNotFoundException
import android.content.Intent
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import com.vector.messenger.design.DesignIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vector.core.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.media.*
import com.vector.core.media.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

@Composable
fun ConversationScreen(chatId: Long, viewModel: ChatsViewModel) {
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val messages = remember(allMessages, chatId) { allMessages.filter { it.chatId == chatId } }
    val newestFirst = remember(messages) { messages.asReversed() }
    val byId = remember(messages) { messages.associateBy { it.id } }
    val chat = chats.find { it.id == chatId }
    var draft by rememberSaveable(chatId, status.session, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var editId by rememberSaveable(chatId, status.session) { mutableStateOf<Long?>(null) }
    var editDraft by rememberSaveable(chatId, status.session, stateSaver = TextFieldValue.Saver) { mutableStateOf(TextFieldValue()) }
    var replyTo by rememberSaveable(chatId, status.session) { mutableStateOf<Long?>(null) }
    var attachmentUri by rememberSaveable(chatId, status.session) { mutableStateOf<String?>(null) }
    var attachmentName by rememberSaveable(chatId, status.session) { mutableStateOf<String?>(null) }
    var attachmentMime by rememberSaveable(chatId, status.session) { mutableStateOf<String?>(null) }
    var fileError by remember(status.session) { mutableStateOf<String?>(null) }
    var actionsFor by remember(status.session) { mutableStateOf<Long?>(null) }
    var selectText by remember(status.session) { mutableStateOf<String?>(null) }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val submitting by viewModel.submitting.collectAsStateWithLifecycle()
    val busy = chatId in submitting
    val listState = rememberLazyListState()
    var previousLastId by rememberSaveable(chatId, status.session) { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val mediaViewModel = LocalMediaViewModel.current
    val voiceViewModel = com.vector.messenger.feature.audio.LocalVoiceViewModel.current
    val voiceState by remember(voiceViewModel) {
        voiceViewModel?.state ?: kotlinx.coroutines.flow.MutableStateFlow(com.vector.core.audio.RecordingState())
    }.collectAsStateWithLifecycle()
    val recording = voiceState.chatId == chatId && voiceState.phase != com.vector.core.audio.RecordingPhase.Idle &&
        voiceState.phase != com.vector.core.audio.RecordingPhase.Failed && voiceState.phase != com.vector.core.audio.RecordingPhase.Uncertain
    LaunchedEffect(voiceViewModel, chatId, status.session) {
        voiceViewModel?.accepted?.collect { accepted -> if (accepted.chatId == chatId && replyTo == accepted.replyTo) replyTo = null }
    }
    var mediaSelection by rememberSaveable(chatId, status.session) { mutableStateOf<Long?>(null) }
    val mediaItems = remember(messages, chat?.protectedContent) {
        messages.mapNotNull { message -> message.media?.takeIf { !message.restricted }?.let {
            MediaItem(chatId, message.id, it, message.canSave && chat?.protectedContent != true)
        } }
    }
    val scope = rememberCoroutineScope()
    val colors = DefaultDesign.colors
    LaunchedEffect(status.session, chat?.protectedContent) { selectText = null; actionsFor = null }
    LifecycleStartEffect(chatId, status.session) {
        viewModel.enter(chatId)
        onStopOrDispose { viewModel.leave(chatId) }
    }
    LaunchedEffect(listState, newestFirst, history, status.session) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.mapNotNull { item -> newestFirst.getOrNull(item.index)?.id } }
            .collect { ids ->
                viewModel.visible(chatId, ids)
                if (status.live && ids.any { it == newestFirst.lastOrNull()?.id } && !history.loading && history.error == null && history.hasMore)
                    viewModel.older(chatId)
            }
    }
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
            // Inserting at index 0 changes canScrollBackward before this effect runs. Use the
            // message identity instead, so a sent bubble stays visible with the keyboard open.
            // Older-page eviction lowers the newest ID and must not pull the reader away.
            if (previousLastId == null || (last.outgoing && last.id > (previousLastId ?: 0))) listState.scrollToItem(0)
            previousLastId = last.id
        }
    }
    Column(Modifier.fillMaxSize().designCanvas(colors.canvas).imePadding()) {
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).testTag("message_list"), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp)) {
            itemsIndexed(newestFirst, key = { _, it -> it.id }, contentType = { _, _ -> "message" }) { index, message ->
                val outgoing = message.outgoing
                val previous = newestFirst.getOrNull(index + 1)
                val grouped = previous != null && previous.outgoing == outgoing && previous.senderId == message.senderId
                Column {
                MessageDateSeparator(message.date, previous?.date)
                Row(Modifier.fillMaxWidth().padding(top = if (grouped) 4.dp else 16.dp).excludeModuleGestures(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    if (!outgoing && chat?.kind == ChatKind.Group) { ChatAvatar(message.senderName ?: chat.title, 28.dp); Spacer(Modifier.width(8.dp)) }
                    Column {
                    if (!outgoing && !grouped && chat?.kind == ChatKind.Group) message.senderName?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.labelSmall) }
                    MessageBubble(message, byId[message.replyTo], grouped,
                        onActions = { actionsFor = message.id }, onReply = { if (!busy && !recording) { editId = null; replyTo = message.id } },
                        onReaction = { viewModel.react(chatId, message.id, it) },
                        mediaViewModel = mediaViewModel, mediaItem = mediaItems.find { it.messageId == message.id },
                        onMedia = { mediaSelection = message.id },
                        onOpenAttachment = { attachment ->
                            try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(attachment.uri.toUri(), attachment.mimeType).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                            catch (_: ActivityNotFoundException) { fileError = "No application can open this file." }
                            catch (_: SecurityException) { fileError = "This file is no longer accessible. Choose it again." }
                        })
                    }
                }
                }
            }
            if (status.live) item(key = "history_controls") {
                Column {
                    if (history.loading) Text("Loading messages…", color = colors.secondary)
                    else if (history.hasMore || history.error != null) DefaultAction(history.error ?: "Load earlier messages", { viewModel.older(chatId) })
                    if (messages.size >= com.vector.core.messaging.SessionChatRepository.WINDOW) DefaultAction("Latest messages", { viewModel.latest(chatId) })
                }
            }
        }
        val reply = byId[replyTo]
        val editingMessage = editId
        AdaptiveMessageInput(
            chatId = chatId, replyTo = replyTo, value = if (editId != null) editDraft else draft,
            onValueChange = { fileError = null; if (editId != null) editDraft = it else draft = it },
            live = status.live, enabled = !status.live || chat?.canSend == true,
            busy = busy, recording = recording, editing = editId != null,
            contextTitle = if (editId != null) "EDITING MESSAGE" else reply?.let {
                "Replying to ${if (it.outgoing) "you" else it.senderName ?: chat?.title.orEmpty()}"
            },
            contextText = if (editId != null) "" else reply?.let { it.text.ifEmpty { it.attachment?.name ?: it.media?.name.orEmpty() } }.orEmpty(),
            onCancelContext = { if (editId != null) { editId = null; editDraft = TextFieldValue() } else replyTo = null },
            attachmentName = attachmentName,
            onRemoveAttachment = { attachmentUri = null; attachmentName = null; attachmentMime = null },
            error = fileError ?: error,
            onAttach = {
                if (status.live) fileError = "File uploads are not supported in this Telegram build. Text and voice messages are available."
                else try { picker.launch(arrayOf("*/*")) }
                catch (_: ActivityNotFoundException) { fileError = "No document picker is available." }
            },
            onSubmit = submit@ {
                // A stale check-button callback must never become a new-message send.
                if (editId != editingMessage) return@submit
                val editing = editingMessage
                if (editing != null) {
                    val submittedEdit = editDraft.text
                    viewModel.edit(chatId, editing, submittedEdit) {
                        if (editId == editing && editDraft.text == submittedEdit) { editId = null; editDraft = TextFieldValue() }
                    }
                } else {
                    val submittedDraft = draft.text
                    val submittedReply = replyTo
                    val submittedUri = attachmentUri
                    val attachment = attachmentUri?.let { Attachment(attachmentName ?: "Document", it, attachmentMime ?: "application/octet-stream") }
                    viewModel.send(chatId, submittedDraft, submittedReply, attachment) {
                        // Preserve any draft/context entered while TDLib accepts the submission.
                        if (draft.text == submittedDraft) draft = TextFieldValue()
                        if (replyTo == submittedReply) replyTo = null
                        if (attachmentUri == submittedUri) { attachmentUri = null; attachmentName = null; attachmentMime = null }
                    }
                }
            },
        )
    }

    mediaSelection?.let { id -> if (mediaViewModel != null) MediaViewer(mediaItems, id, mediaViewModel) { mediaSelection = null } }
    byId[actionsFor]?.let { selected ->
        DefaultDialog("Message", { actionsFor = null }) {
            if (!status.live) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("👍", "❤", "✨").forEach { reaction -> DefaultAction(reaction, { viewModel.react(chatId, selected.id, reaction); actionsFor = null }, Modifier.weight(1f)) }
            }
            DefaultAction("Reply", { editId = null; replyTo = selected.id; actionsFor = null }, Modifier.fillMaxWidth(), enabled = !busy && !recording)
            if (selected.outgoing && selected.text.isNotBlank() && selected.media == null && selected.attachment == null &&
                !selected.restricted && selected.delivery in listOf(Delivery.LocalDemo, Delivery.Sent, Delivery.Read)) {
                DefaultAction("Edit message", {
                    editId = selected.id
                    editDraft = TextFieldValue(selected.text, TextRange(selected.text.length))
                    actionsFor = null
                }, Modifier.fillMaxWidth(), enabled = !busy && !recording)
            }
            if (chat?.protectedContent != true && selected.canSave) DefaultAction("Select text", { selectText = selected.text; actionsFor = null }, Modifier.fillMaxWidth())
            if (selected.delivery == Delivery.Failed && selected.canRetry) {
                val ready = selected.retryAt <= System.currentTimeMillis()
                DefaultAction(if (ready) "Retry message" else "Retry later", { viewModel.retry(chatId, selected.id); actionsFor = null }, Modifier.fillMaxWidth(), enabled = ready)
            }
            DefaultAction("Done", { actionsFor = null }, Modifier.fillMaxWidth())
        }
    }
    selectText?.takeIf { chat?.protectedContent != true }?.let { text -> DefaultDialog("Select text", { selectText = null }) {
        SelectionContainer { Text(text, color = colors.text, modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) }
        DefaultAction("Done", { selectText = null }, Modifier.fillMaxWidth())
    } }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(message: Message, reply: Message?, grouped: Boolean,
    onActions: () -> Unit, onReply: () -> Unit, onReaction: (String) -> Unit, onOpenAttachment: (Attachment) -> Unit,
    mediaViewModel: MediaViewModel?, mediaItem: MediaItem?, onMedia: () -> Unit,
) {
    val outgoing = message.outgoing
    val colors = DefaultDesign.colors
    var drag by remember { mutableFloatStateOf(0f) }
    val translation = animateFloatAsState(drag, VectorMotionSpec.replyFollow(LocalAppearance.current), label = "Swipe to reply")
    val replyDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val currentReply by rememberUpdatedState(onReply)
    val shape = DefaultDesign.messageShape(outgoing, grouped)
    Column(Modifier.widthIn(max = 310.dp).graphicsLayer { translationX = translation.value }
        .pointerInput(message.id, replyDistance) {
            detectHorizontalDragGestures(onDragEnd = { if (drag >= replyDistance) currentReply(); drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                change.consume(); drag = max(0f, drag + amount).coerceAtMost(replyDistance * 1.2f)
            }
        }
        .background(if (outgoing) colors.outgoing else colors.panel.copy(alpha = .78f), shape)
        .then(Modifier.designFrame(shape, tint = if (outgoing) colors.accent.copy(alpha = .30f) else colors.text.copy(alpha = .07f)))
        .combinedClickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {}, onLongClick = onActions)
        .padding(horizontal = 14.dp, vertical = 12.dp)) {
        if (message.replyTo != null) {
            Row(Modifier.padding(bottom = 10.dp)) {
                Box(Modifier.width(2.dp).height(34.dp).background(colors.accent))
                Column(Modifier.padding(start = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Reply", style = MaterialTheme.typography.labelSmall, color = colors.accent)
                    Text(reply?.text?.ifEmpty { reply.attachment?.name.orEmpty() } ?: "Earlier message", color = colors.secondary, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (mediaItem != null && mediaViewModel != null) {
            if (mediaItem.media.kind in listOf(MediaKind.Voice, MediaKind.Audio)) com.vector.messenger.feature.audio.VoiceAttachment(mediaItem, mediaViewModel)
            else MediaAttachmentView(mediaItem, mediaViewModel, onMedia)
        }
        message.attachment?.let { attachment -> DefaultAction(attachment.name, { onOpenAttachment(attachment) }, Modifier.padding(bottom = 8.dp), VectorIcons.Attach) }
        if (message.text.isNotEmpty()) Row {
            Text(message.text, color = colors.text, style = MaterialTheme.typography.bodyLarge)
        }
        Row(Modifier.align(Alignment.End).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(message.time, style = MaterialTheme.typography.labelSmall, color = colors.secondary)
            if (outgoing) {
                Box(Modifier.width(1.dp).height(10.dp).background(colors.line))
                if (message.delivery == Delivery.LocalDemo) Icon(VectorIcons.Clock, "Stored locally; not sent to Telegram", Modifier.size(12.dp), tint = colors.accent)
                Text(when (message.delivery) {
                    Delivery.LocalDemo -> "Local demo"
                    Delivery.Sending -> "Sending…"
                    Delivery.Failed -> "Failed"
                    Delivery.Read -> "✓✓"
                    Delivery.Sent -> "✓"
                    else -> ""
                }, style = MaterialTheme.typography.labelSmall, color = if (message.delivery == Delivery.Read) colors.active else colors.secondary,
                    modifier = Modifier.semantics { contentDescription = when (message.delivery) {
                        Delivery.Sent -> "Sent to Telegram"
                        Delivery.Read -> "Read"
                        Delivery.Failed -> "Message failed to send"
                        Delivery.Sending -> "Sending message"
                        else -> "Local demonstration message"
                    } })
            }
        }
        message.reaction?.let { reaction -> DefaultAction("$reaction  1", { onReaction(reaction) }, Modifier.padding(top = 8.dp)) }
    }
}
