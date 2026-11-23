package com.vector.messenger.feature

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.sp
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
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
    val photoError by viewModel.photoError.collectAsStateWithLifecycle()
    var selectedPhotos by rememberSaveable(chatId, status.session) { mutableStateOf(arrayListOf<String>()) }
    var photoCaption by rememberSaveable(chatId, status.session) { mutableStateOf("") }
    var photoDraftSource by rememberSaveable(chatId, status.session) { mutableStateOf("") }
    var photoAlbum by rememberSaveable(chatId, status.session) { mutableStateOf(true) }
    var photoUncertain by rememberSaveable(chatId, status.session) { mutableStateOf(false) }
    var galleryOpen by rememberSaveable(chatId, status.session) { mutableStateOf(false) }
    var previewOpen by rememberSaveable(chatId, status.session) { mutableStateOf(false) }
    var previewInitial by rememberSaveable(chatId, status.session) { mutableStateOf<String?>(null) }
    var qualityName by rememberSaveable(chatId, status.session) { mutableStateOf(PhotoQuality.STANDARD.name) }
    val photoQuality = PhotoQuality.valueOf(qualityName)
    var photoAccessError by remember(chatId, status.session) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val headerInset = LocalConversationHeaderInset.current
    val visibleTop = with(LocalDensity.current) { headerInset.toPx() }
    var previousLastId by rememberSaveable(chatId, status.session) { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    val attachmentKeyboard = LocalSoftwareKeyboardController.current
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
    val toolRequest by viewModel.chatTool.collectAsStateWithLifecycle()
    var chatTool by rememberSaveable(chatId, status.session) { mutableStateOf<String?>(null) }
    var chatSearch by rememberSaveable(chatId, status.session) { mutableStateOf("") }
    val mediaItems = remember(messages, chat?.protectedContent) {
        messages.mapNotNull { message -> message.media?.takeIf { !message.restricted }?.let {
            MediaItem(chatId, message.id, it, message.canSave && chat?.protectedContent != true)
        } }
    }
    LaunchedEffect(toolRequest) {
        val request = toolRequest?.takeIf { it.chatId == chatId } ?: return@LaunchedEffect
        if (request.name == "media") mediaSelection = mediaItems.lastOrNull()?.messageId
        else chatTool = request.name
        viewModel.consumeChatTool(request)
    }
    val scope = rememberCoroutineScope()
    val colors = DefaultDesign.colors
    LaunchedEffect(status.session, chat?.protectedContent) { selectText = null; actionsFor = null }
    LifecycleStartEffect(chatId, status.session) {
        viewModel.enter(chatId)
        onStopOrDispose { viewModel.leave(chatId) }
    }
    LaunchedEffect(listState, newestFirst, history, status.session, visibleTop) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.filter { it.offset + it.size > visibleTop }.mapNotNull { item -> newestFirst.getOrNull(item.index)?.id } }
            .collect { ids ->
                viewModel.visible(chatId, ids)
                if (status.live && ids.any { it == newestFirst.lastOrNull()?.id } && !history.loading && history.error == null && history.hasMore)
                    viewModel.older(chatId)
            }
    }
    fun submitSelectedPhotos() {
        if (!galleryOpen || selectedPhotos.isEmpty() || busy || photoUncertain) return
        val selection = selectedPhotos.toList()
        val caption = photoCaption
        val reply = replyTo
        val session = status.session
        viewModel.sendPhotos(chatId, selection, caption, photoAlbum, reply, photoQuality) { result ->
            if (viewModel.status.value.session == session) {
                selectedPhotos = ArrayList(selection.drop(result.accepted))
                photoUncertain = result.result == SendResult.Uncertain
                if (result.accepted > 0) {
                    photoCaption = ""
                    if (draft.text == photoDraftSource) draft = TextFieldValue()
                    if (replyTo == reply) replyTo = null
                }
                if (selectedPhotos.isEmpty()) { galleryOpen = false; previewOpen = false }
            }
        }
    }
    val originalPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && photoQuality == PhotoQuality.FILE) { photoAccessError = null; submitSelectedPhotos() }
        else if (!granted) photoAccessError = "FILE requires original media access to preserve every byte, including metadata. Allow it in app permissions or choose Standard / HD."
    }
    fun sendSelectedPhotos() {
        if (photoQuality == PhotoQuality.FILE && androidx.core.content.ContextCompat.checkSelfPermission(context,
                android.Manifest.permission.ACCESS_MEDIA_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            originalPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)
        } else submitSelectedPhotos()
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
        LazyColumn(state = listState, reverseLayout = true, modifier = Modifier.weight(1f).conversationMessageDissolve().testTag("message_list"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = headerInset + 20.dp, bottom = 20.dp)) {
            itemsIndexed(newestFirst, key = { _, it -> it.id }, contentType = { _, _ -> "message" }) { index, message ->
                val outgoing = message.outgoing
                val previous = newestFirst.getOrNull(index + 1)
                val next = newestFirst.getOrNull(index - 1)
                val position = remember(message.id, message.senderId, message.senderIsChat, message.date, message.isService, previous?.id, previous?.date, previous?.senderId, previous?.isService, next?.id, next?.date, next?.senderId, next?.isService) {
                    MessageGrouping.position(previous, message, next)
                }
                val grouped = position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.LAST
                Column {
                MessageDateSeparator(message.date, previous?.date)
                Row(Modifier.fillMaxWidth().padding(top = if (grouped) 2.dp else 8.dp).excludeModuleGestures(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Bottom) {
                    if (!outgoing && chat?.kind == ChatKind.Group) { TelegramAvatar(message.senderName ?: chat.title, if (message.senderId != 0L) null else chat.photo, 28.dp, if (message.senderId != 0L) message.senderId else chat.id, user = message.senderId != 0L && !message.senderIsChat); Spacer(Modifier.width(8.dp)) }
                    Column {
                    if (!outgoing && !grouped && chat?.kind == ChatKind.Group) message.senderName?.let { Text(it, color = colors.accent, style = MaterialTheme.typography.labelSmall) }
                    val statusVisible by remember(listState, message.id, visibleTop) {
                        derivedStateOf { listState.layoutInfo.visibleItemsInfo.any { it.key == message.id && it.offset + it.size > visibleTop } }
                    }
                    MessageBubble(message, byId[message.replyTo], position, statusVisible = statusVisible,
                        statusTransitions = viewModel.messageStatusTransitions, statusSession = status.session,
                        onActions = { actionsFor = message.id }, onReply = { if (!busy && !recording) { editId = null; replyTo = message.id } },
                        onReaction = { viewModel.react(chatId, message.id, it) },
                        onCancelUpload = { viewModel.cancelPhotoSend(chatId, message.id) },
                        onRetryUpload = { viewModel.retry(chatId, message.id) },
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
                attachmentKeyboard?.hide()
                selectedPhotos = arrayListOf(); galleryOpen = true; previewOpen = false
                photoCaption = draft.text; photoDraftSource = draft.text
                photoAlbum = true; photoUncertain = false; qualityName = PhotoQuality.STANDARD.name
                photoAccessError = null; viewModel.clearPhotoError()
            },
            attachmentsEnabled = !status.live || chat?.canSendPhotos == true || chat?.canSendDocuments == true,
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

    if (galleryOpen) PhotoGalleryPanel(viewModel, selectedPhotos, photoQuality, busy, photoUncertain,
        photoAccessError ?: photoError,
        onSelection = { selectedPhotos = ArrayList(it); if (it.isEmpty()) previewOpen = false },
        onPreview = { previewInitial = it; previewOpen = true }, onSend = ::sendSelectedPhotos,
        onDismiss = { galleryOpen = false; previewOpen = false; selectedPhotos = arrayListOf(); viewModel.clearPhotoError() })
    if (galleryOpen && previewOpen && selectedPhotos.isNotEmpty()) PhotoSendPreview(selectedPhotos, photoCaption, photoAlbum, busy, photoUncertain,
        photoAccessError ?: photoError, viewModel,
        onCaption = { photoCaption = it; viewModel.clearPhotoError() }, onAlbum = { photoAlbum = it },
        onRemove = { uri -> selectedPhotos = ArrayList(selectedPhotos.filterNot { it == uri }); if (selectedPhotos.isEmpty()) previewOpen = false },
        onDismiss = { previewOpen = false }, onSend = ::sendSelectedPhotos, initialUri = previewInitial,
        quality = photoQuality, onQuality = { qualityName = it.name; photoAccessError = null; viewModel.clearPhotoError() })
    if (chatTool == "info") DefaultDialog("Conversation info", { chatTool = null }) {
        chat?.let { current ->
            TelegramAvatar(current.title, current.photo, 64.dp, current.userId ?: current.id, user = current.userId != null)
            Text(current.title, color = colors.text, style = MaterialTheme.typography.titleLarge)
            Text("${current.kind} · ${if (status.live) "Telegram" else "Demo conversation"}", color = colors.secondary)
            Text("Conversation ID: ${current.id}", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
        }
        DefaultAction("Done", { chatTool = null })
    }
    if (chatTool == "search") DefaultDialog("Search in chat", { chatTool = null }) {
        DefaultInput(chatSearch, { chatSearch = it }, "Search loaded messages", Modifier.fillMaxWidth())
        Text("Searches currently loaded history.", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
        val matches = remember(messages, chatSearch) { if (chatSearch.isBlank()) emptyList() else messages.filter { it.text.contains(chatSearch.trim(), ignoreCase = true) } }
        LazyColumn(Modifier.heightIn(max = 300.dp)) {
            itemsIndexed(matches, key = { _, item -> item.id }) { _, found ->
                Column(Modifier.fillMaxWidth().clickable {
                    chatTool = null
                    val index = newestFirst.indexOfFirst { it.id == found.id }
                    if (index >= 0) scope.launch { listState.scrollToItem(index) }
                }.padding(vertical = 12.dp)) {
                    Text(found.text, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(found.time, color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (chatSearch.isNotBlank() && matches.isEmpty()) Text("No matching loaded messages", color = colors.secondary)
        DefaultAction("Done", { chatTool = null })
    }
    mediaSelection?.let { id -> if (mediaViewModel != null) MediaViewer(if (mediaItems.find { it.messageId == id }?.media?.kind == MediaKind.Photo) mediaItems.filter { it.media.kind == MediaKind.Photo } else mediaItems, id, mediaViewModel) { mediaSelection = null } }
    byId[actionsFor]?.let { selected ->
        DefaultDialog("Message", { actionsFor = null }) {
            if (!status.live) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("👍", "❤", "✨").forEach { reaction -> DefaultAction(reaction, { viewModel.react(chatId, selected.id, reaction); actionsFor = null }, Modifier.weight(1f)) }
            }
            if (status.live && !selected.restricted && !selected.isService && selected.delivery !in listOf(Delivery.Sending, Delivery.Failed)) {
                val heartChosen = selected.reactions.any { it.emoji.replace("\uFE0F", "") == "❤" && it.chosen }
                DefaultAction(if (heartChosen) "Remove ❤ reaction" else "React with ❤", { viewModel.react(chatId, selected.id, "❤"); actionsFor = null }, Modifier.fillMaxWidth())
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
private fun MessageBubble(message: Message, reply: Message?, position: MessageGroupPosition,
    onActions: () -> Unit, onReply: () -> Unit, onReaction: (String) -> Unit, onCancelUpload: () -> Unit, onRetryUpload: () -> Unit, onOpenAttachment: (Attachment) -> Unit,
    mediaViewModel: MediaViewModel?, mediaItem: MediaItem?, onMedia: () -> Unit, statusVisible: Boolean,
    statusTransitions: MessageStatusTransitions, statusSession: Long,
) {
    val outgoing = message.outgoing
    val colors = DefaultDesign.colors
    var drag by remember { mutableFloatStateOf(0f) }
    val translation = animateFloatAsState(drag, VectorMotionSpec.replyFollow(LocalAppearance.current), label = "Swipe to reply")
    val replyDistance = with(LocalDensity.current) { 48.dp.toPx() }
    val currentReply by rememberUpdatedState(onReply)
    val shape = remember(position, outgoing) { DefaultDesign.messageShape(position, outgoing) }
    val appearance = LocalAppearance.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val pulse = remember(message.id) { Animatable(1f) }
    var feedbackJob by remember(message.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val currentReaction by rememberUpdatedState(onReaction)
    val canDoubleReact = !message.restricted && !message.isService && message.media == null && message.attachment == null &&
        message.delivery !in listOf(Delivery.Sending, Delivery.Failed)
    Column(Modifier.widthIn(max = 310.dp).graphicsLayer { translationX = translation.value; scaleX = pulse.value; scaleY = pulse.value }.clip(shape)
        .pointerInput(message.id, replyDistance) {
            detectHorizontalDragGestures(onDragEnd = { if (drag >= replyDistance) currentReply(); drag = 0f }, onDragCancel = { drag = 0f }) { change, amount ->
                change.consume(); drag = max(0f, drag + amount).coerceAtMost(replyDistance * 1.2f)
            }
        }
        .background(if (outgoing) colors.outgoing else colors.panel.copy(alpha = .78f), shape)
        .then(Modifier.designFrame(shape, tint = if (outgoing) colors.accent.copy(alpha = .30f) else colors.text.copy(alpha = .07f)))
        .combinedClickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, onClick = {}, onLongClick = onActions,
            onDoubleClick = if (canDoubleReact) ({
                if (feedbackJob?.isActive != true) {
                    currentReaction("❤")
                    if (appearance.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    feedbackJob = scope.launch {
                        try {
                            pulse.animateTo(VectorMotionSpec.pressScale(.98f, appearance), VectorMotionSpec.lockPeak(appearance))
                            pulse.animateTo(1f, VectorMotionSpec.feedback(appearance))
                        } finally { pulse.snapTo(1f) }
                    }
                }
            }) else null)
        .padding(horizontal = 12.dp, vertical = 8.dp)) {
        val reactions = message.reactions.ifEmpty { message.reaction?.let { listOf(MessageReaction(it, 1, true)) }.orEmpty() }
        CompactMessageContent(outgoing, reactions, onReaction) {
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
                else MediaAttachmentView(mediaItem, mediaViewModel, onMedia, message.delivery,
                    onCancelUpload, onRetryUpload, message.canRetry, message.retryAt)
            }
            message.attachment?.let { attachment -> DefaultAction(attachment.name, { onOpenAttachment(attachment) }, Modifier.padding(bottom = 8.dp), VectorIcons.Attach) }
            // Both directions share a bottom-aligned, measured text/time line. Media and
            // reply previews above retain their width and existing interactive controls.
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                if (message.text.isNotEmpty()) Text(message.text, Modifier.weight(1f, fill = false),
                    color = colors.text, style = MaterialTheme.typography.bodyLarge)
                MessageTimestamp(message, statusVisible = statusVisible, transitions = statusTransitions, session = statusSession)
            }
        }
    }
}

/** Measure message content first; reactions can wrap within that width rather than stretching it.
 * Only a single indivisible emoji/count wider than the body may increase the bubble width. */
@Composable
private fun CompactMessageContent(
    outgoing: Boolean, reactions: List<MessageReaction>, onReaction: (String) -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = DefaultDesign.colors
    Layout(content = {
        Column(content = content)
        if (reactions.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp, if (outgoing) Alignment.End else Alignment.Start),
            verticalArrangement = Arrangement.spacedBy(1.dp)) {
            reactions.forEach { reaction -> key(reaction.emoji) {
                Row(Modifier.heightIn(min = 20.dp)
                    .clickable(role = Role.Button, onClickLabel = if (reaction.chosen) "Remove ${reaction.emoji} reaction" else "React ${reaction.emoji}") { onReaction(reaction.emoji) }
                    .semantics { selected = reaction.chosen },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(reaction.emoji, fontSize = 13.sp, lineHeight = 16.sp)
                    Text(reaction.count.toString(), color = if (reaction.chosen) colors.accent else colors.secondary,
                        fontSize = 11.sp, lineHeight = 14.sp)
                }
            } }
        }
    }) { measurables, constraints ->
        val body = measurables[0].measure(constraints.copy(minWidth = 0, minHeight = 0))
        val reactionContent = measurables.getOrNull(1)
        val reactionMinimum = reactionContent?.minIntrinsicWidth(Constraints.Infinity)?.coerceAtMost(constraints.maxWidth) ?: 0
        val width = constraints.constrainWidth(maxOf(body.width, reactionMinimum))
        val gap = if (reactionContent != null) 1.dp.roundToPx() else 0
        val remainingHeight = if (constraints.hasBoundedHeight) (constraints.maxHeight - body.height - gap).coerceAtLeast(0) else Constraints.Infinity
        val reactionRow = reactionContent?.measure(Constraints(minWidth = width, maxWidth = width, maxHeight = remainingHeight))
        layout(width, constraints.constrainHeight(body.height + gap + (reactionRow?.height ?: 0))) {
            body.placeRelative(width - body.width, 0)
            reactionRow?.placeRelative(0, body.height + gap)
        }
    }
}


