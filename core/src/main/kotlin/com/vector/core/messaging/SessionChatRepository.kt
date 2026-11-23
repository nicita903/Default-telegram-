package com.vector.core.messaging

import com.vector.core.*
import com.vector.core.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** One source per authorization session. All reducers run on the injected Main scope. */
class SessionChatRepository(
    authentication: AuthenticationRepository, private val client: TelegramMessagingClient,
    private val demo: ChatRepository, private val scope: CoroutineScope,
) : ChatRepository {
    private val chatState = MutableStateFlow(demo.chats.value)
    private val messageState = MutableStateFlow(demo.messages.value)
    private val statusState = MutableStateFlow(ChatStatus())
    private val historyState = MutableStateFlow(HistoryState())
    private val reactionErrorState = MutableStateFlow<String?>(null)
    override val reactionError = reactionErrorState.asStateFlow()
    override val chats = chatState.asStateFlow()
    override val messages = messageState.asStateFlow()
    override val status = statusState.asStateFlow()
    override val history = historyState.asStateFlow()
    private val arrivalEvents = MutableSharedFlow<MessageArrival>(extraBufferCapacity = 128)
    override val arrivals = arrivalEvents.asSharedFlow()
    private val seenArrivals = linkedSetOf<Long>()
    private var viewportNearLatest = true
    private var viewportScrolling = false
    override fun reportViewport(chatId: Long, nearLatest: Boolean, scrolling: Boolean) {
        if (active == chatId) { viewportNearLatest = nearLatest; viewportScrolling = scrolling }
    }
    private val records = mutableMapOf<Long, TelegramChat>()
    private val access = mutableMapOf<Pair<Long, Boolean>, ChatUpdate.Access>()
    private var orderDirty = true
    private var ordered = emptyList<Pair<Long, ChatPosition>>()
    private val window = mutableMapOf<Long, Message>()
    private var messageOrder = emptyList<Long>()
    private val versions = mutableMapOf<Long, Long>()
    private val replaced = linkedSetOf<Long>()
    private var revision = 0L
    private var sessionScope: CoroutineScope? = null
    private var publish: Job? = null
    private var historyJob: Job? = null
    private var historyGeneration = 0L
    private val listJobs = mutableMapOf<Boolean, Job>()
    private val avatarRequests = mutableSetOf<Int>()
    private val visibleReads = mutableSetOf<Long>()
    private val sends = mutableSetOf<Long>()
    private val retries = mutableSetOf<Long>()
    private val reactionRequests = mutableSetOf<Pair<Long, Long>>()
    private val photoCancellations = mutableSetOf<Pair<Long, Long>>()
    private var active: Long? = null
    private var atLatest = true
    private var live = false
    private var epoch = 0L

    init {
        scope.launch {
            authentication.state.map { it.step == AuthStep.Ready }.distinctUntilChanged().collectLatest { ready ->
                epoch++; live = ready; active = null; atLatest = true; seenArrivals.clear(); viewportNearLatest = true; viewportScrolling = false; historyGeneration++
                records.clear(); access.clear(); window.clear(); versions.clear(); replaced.clear(); visibleReads.clear(); reactionRequests.clear(); reactionErrorState.value = null
                orderDirty = true; ordered = emptyList()
                sends.clear(); retries.clear(); photoCancellations.clear(); avatarRequests.clear(); listJobs.clear(); publish = null; historyJob = null
                messageState.value = if (ready) emptyList() else demo.messages.value
                chatState.value = if (ready) emptyList() else demo.chats.value
                historyState.value = HistoryState()
                statusState.value = ChatStatus(live = ready, session = epoch, mainMore = ready, archiveMore = ready, connection = client.connection.value)
                coroutineScope {
                    sessionScope = this
                    try {
                        if (!ready) {
                            launch { demo.chats.collect { chatState.value = it } }
                            launch { demo.messages.collect { messageState.value = it } }
                            awaitCancellation()
                        } else {
                            launch(start = CoroutineStart.UNDISPATCHED) { client.updates.collect(::reduce) }
                            launch { client.connection.collect { connection -> statusState.update { it.copy(connection = connection) } } }
                            loadMoreChats(false); loadMoreChats(true)
                            awaitCancellation()
                        }
                    } finally { sessionScope = null }
                }
            }
        }
    }

    private fun scheduleChats() {
        if (publish?.isActive == true) return
        publish = sessionScope?.launch {
            yield() // Coalesce a receive burst without a timer or periodic refresh.
            if (orderDirty) {
                ordered = records.values.mapNotNull { record ->
                    val position = record.positions.firstOrNull { !it.archived && it.order != 0L }
                        ?: record.positions.firstOrNull { it.archived && it.order != 0L } ?: return@mapNotNull null
                    record.chat.id to position
                }.sortedWith(compareByDescending<Pair<Long, ChatPosition>> { it.second.order }.thenByDescending { it.first })
                orderDirty = false
            }
            chatState.value = ordered.mapNotNull { (id, position) -> records[id]?.let {
                val chat = projected(it)
                if (chat.archived == position.archived && chat.pinned == position.pinned && chat.order == position.order) chat
                else chat.copy(archived = position.archived, pinned = position.pinned, order = position.order)
            } }
        }
    }
    private fun projected(record: TelegramChat): Chat {
        val rule = access[record.peerId to record.supergroup]
        return if (rule?.restricted == true) record.chat.copy(preview = "Content unavailable", canSend = false, canSendPhotos = false, canSendDocuments = false, protectedContent = true, photo = null)
        else {
            val chat = record.chat
            val canSend = rule?.canSendOverride ?: chat.canSend
            val canSendPhotos = rule?.canSendPhotosOverride ?: chat.canSendPhotos
            if (chat.canSend == canSend && chat.canSendPhotos == canSendPhotos) chat
            else chat.copy(canSend = canSend, canSendPhotos = canSendPhotos)
        }
    }
    private fun change(id: Long, transform: (TelegramChat) -> TelegramChat) {
        val old = records[id] ?: return
        val next = transform(old)
        if (next != old) { if (next.positions != old.positions) orderDirty = true; records[id] = next; scheduleChats() }
    }
    private fun normalize(message: Message): Message {
        val read = records[message.chatId]?.lastReadOutbox ?: 0
        return if (message.outgoing && message.delivery == Delivery.Sent && message.id <= read) message.copy(delivery = Delivery.Read) else message
    }
    private fun put(message: Message, event: Boolean = true) {
        if (message.chatId != active || message.id in replaced) return
        // Once newer pages have been evicted, don't stitch distant live messages onto old history.
        // The explicit Latest action restores a contiguous newest window from TDLib.
        val newNewest = message.id !in window && message.id > (window.keys.maxOrNull() ?: 0)
        if (event && newNewest && !viewportNearLatest && window.size >= WINDOW) {
            atLatest = false
            historyState.update { it.copy(atLatest = false) }
        }
        if (event && !atLatest && newNewest) return
        val record = records[message.chatId]
        if (record != null && access[record.peerId to record.supergroup]?.restricted == true) return
        if (event) { revision++; versions[message.id] = revision }
        window[message.id] = normalize(message).let { it.copy(senderName = access[it.senderId to false]?.displayName ?: it.senderName) }
    }
    private fun publishMessages(older: Boolean = false) {
        // Reactions, read receipts and media progress do not change message ordering.
        if (messageOrder.size != window.size || messageOrder.any { it !in window }) messageOrder = window.keys.sorted()
        if (older && messageOrder.size > WINDOW) atLatest = false
        if (messageOrder.size > WINDOW) {
            messageOrder = if (older || !atLatest) messageOrder.take(WINDOW) else messageOrder.takeLast(WINDOW)
            window.keys.retainAll(messageOrder.toHashSet())
        }
        val bounded = messageOrder.mapNotNull(window::get)
        historyState.update { it.copy(atLatest = atLatest) }
        visibleReads.retainAll(window.keys)
        if (versions.size > WINDOW * 3) versions.keys.retainAll(window.keys)
        messageState.value = bounded
    }
    private fun reduce(update: ChatUpdate) {
        if (!live) return
        when (update) {
            is ChatUpdate.Snapshot -> { records[update.value.chat.id] = update.value; orderDirty = true; scheduleChats() }
            is ChatUpdate.Position -> change(update.id) { it.copy(positions = it.positions.filterNot { p -> p.archived == update.value.archived } + update.value) }
            is ChatUpdate.Positions -> change(update.id) { it.copy(positions = update.values) }
            is ChatUpdate.Preview -> change(update.id) { it.copy(chat = it.chat.copy(preview = update.text, time = update.time), lastMessageId = update.lastId, positions = update.positions, lastRestricted = update.restricted) }
            is ChatUpdate.Title -> change(update.id) { it.copy(chat = it.chat.copy(title = update.title)) }
            is ChatUpdate.Photo -> change(update.id) { it.copy(chat = it.chat.copy(photo = update.photo)) }
            is ChatUpdate.Unread -> change(update.id) { it.copy(chat = it.chat.copy(unread = update.count)) }
            is ChatUpdate.Permissions -> change(update.id) { it.copy(chat = it.chat.copy(canSend = update.canSend, canSendPhotos = update.canSendPhotos, canSendDocuments = update.canSendDocuments)) }
            is ChatUpdate.Protection -> change(update.id) { it.copy(chat = it.chat.copy(protectedContent = update.protected)) }
            is ChatUpdate.ReadOutbox -> {
                change(update.id) { it.copy(lastReadOutbox = update.lastId) }
                if (active == update.id) { window.replaceAll { _, message -> normalize(message) }; publishMessages() }
            }
            is ChatUpdate.Upsert -> if (active == update.message.chatId) {
                val fresh = update.isNew && update.message.id !in seenArrivals && update.message.id !in window
                val follow = viewportNearLatest && !viewportScrolling && atLatest
                update.oldId?.takeIf { it != update.message.id }?.let { old ->
                    replaced += old; if (replaced.size > WINDOW * 3) replaced.remove(replaced.first())
                    window.remove(old)
                }
                put(update.message); publishMessages()
                if (fresh) {
                    seenArrivals += update.message.id
                    while (seenArrivals.size > WINDOW * 3) seenArrivals.remove(seenArrivals.first())
                    arrivalEvents.tryEmit(MessageArrival(epoch, update.message.chatId, update.message.id, update.message.outgoing,
                        follow, update.message.outgoing && update.message.delivery == Delivery.Sending))
                }
            }
            is ChatUpdate.Content -> {
                if (records[update.chatId]?.let { it.lastMessageId == update.messageId && !it.lastRestricted } == true)
                    change(update.chatId) { it.copy(chat = it.chat.copy(preview = update.text.ifEmpty {
                        when (update.media?.kind) {
                            com.vector.core.media.MediaKind.Photo -> "Photo"
                            com.vector.core.media.MediaKind.Document -> update.media.name
                            else -> ""
                        }
                    })) }
                if (active == update.chatId) {
                    revision++; versions[update.messageId] = revision
                    window[update.messageId]?.takeUnless { it.restricted }?.let { window[update.messageId] = it.copy(text = update.text, media = update.media) }; publishMessages()
                }
            }
            is ChatUpdate.Reactions -> if (active == update.chatId) {
                revision++; versions[update.messageId] = revision
                window[update.messageId]?.takeUnless { it.restricted }?.let { window[update.messageId] = it.copy(reactions = update.reactions) }
                publishMessages()
            }
            is ChatUpdate.Deleted -> if (active == update.chatId) {
                update.ids.forEach { id -> revision++; versions[id] = revision; window.remove(id) }; publishMessages()
            }
            is ChatUpdate.File -> {
                records.values.filter { it.chat.photo?.fileId == update.photo.fileId && it.chat.photo != update.photo }.map { it.chat.id }.forEach { id ->
                    change(id) { it.copy(chat = it.chat.copy(photo = update.photo)) }
                }
            }
            is ChatUpdate.Access -> {
                access[update.peerId to update.supergroup] = update
                if (records.values.any { it.peerId == update.peerId && it.supergroup == update.supergroup }) scheduleChats()
                if (!update.supergroup && update.displayName != null) {
                    window.replaceAll { _, message -> if (message.senderId == update.peerId) message.copy(senderName = update.displayName) else message }
                    publishMessages()
                }
                val current = active?.let(records::get)
                if (update.restricted && current?.peerId == update.peerId && current.supergroup == update.supergroup) {
                    historyJob?.cancel(); historyGeneration++; window.clear(); messageState.value = emptyList()
                    historyState.value = HistoryState(active, hasMore = false, error = "Content unavailable in this client.")
                }
            }
        }
    }
    override fun loadMoreChats(archived: Boolean) {
        if (!live || listJobs[archived]?.isActive == true) return
        if (!(if (archived) status.value.archiveMore else status.value.mainMore)) return
        listJobs[archived] = sessionScope?.launch(start = CoroutineStart.LAZY) {
            statusState.update { it.copy(loading = true, error = null) }
            try {
                val more = client.loadChats(archived, 100)
                ensureActive()
                statusState.update { if (archived) it.copy(archiveMore = more) else it.copy(mainMore = more) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { statusState.update { it.copy(error = "Unable to load Telegram chats. Check your connection and retry.") } }
            finally { statusState.update { it.copy(loading = listJobs.any { (key, job) -> key != archived && job.isActive }) } }
        } ?: return
        listJobs[archived]?.start()
    }
    override fun openConversation(chatId: Long) {
        if (!live || active == chatId) return
        val old = active; active = chatId; atLatest = true; viewportNearLatest = true; viewportScrolling = false; seenArrivals.clear(); historyGeneration++; historyJob?.cancel()
        window.clear(); versions.clear(); replaced.clear(); visibleReads.clear(); messageState.value = emptyList()
        historyState.value = HistoryState(chatId)
        sessionScope?.launch { try { old?.let { client.closeChat(it) }; client.openChat(chatId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {} }
        sessionScope?.launch {
            try {
                val allowed = client.photoPermission(chatId)
                val documents = client.photoPermission(chatId, true)
                ensureActive()
                if (active == chatId) change(chatId) { it.copy(chat = it.chat.copy(canSendPhotos = allowed, canSendDocuments = documents)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Keep the last confirmed permission; TDLib rechecks on send. */ }
        }
        loadOlder(chatId)
    }
    override fun closeConversation(chatId: Long) {
        if (!live || active != chatId) return
        active = null; historyGeneration++; historyJob?.cancel(); window.clear(); messageState.value = emptyList(); historyState.value = HistoryState()
        sessionScope?.launch { try { client.closeChat(chatId) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {} }
    }
    override fun loadOlder(chatId: Long) {
        if (!live || active != chatId || historyJob?.isActive == true || !history.value.hasMore) return
        if (records[chatId]?.let { access[it.peerId to it.supergroup]?.restricted } == true) {
            historyState.value = HistoryState(chatId, hasMore = false, error = "Content unavailable in this client."); return
        }
        val generation = historyGeneration
        val from = window.keys.minOrNull() ?: 0
        val startedAt = revision
        historyJob = sessionScope?.launch {
            historyState.value = HistoryState(chatId, loading = true, atLatest = atLatest)
            try {
                val page = client.history(chatId, from, PAGE)
                ensureActive(); if (generation != historyGeneration) return@launch
                val newIds = page.any { it.id != from && it.id !in window && it.id !in replaced }
                page.forEach { message -> if ((versions[message.id] ?: 0) <= startedAt) put(message, event = false) }
                publishMessages(older = from != 0L)
                historyState.value = HistoryState(chatId, hasMore = newIds, atLatest = atLatest)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == historyGeneration) historyState.value = HistoryState(chatId, error = "Unable to load messages. Tap to retry.", atLatest = atLatest) }
        }
    }
    override suspend fun send(chatId: Long, text: String, replyTo: Long?, attachment: Attachment?): SendResult {
        if (!live) return demo.send(chatId, text, replyTo, attachment)
        if (attachment != null) return SendResult.Unsupported
        if (text.isBlank()) return SendResult.Empty
        if (text.length > 4096) return SendResult.TooLong
        val record = records[chatId] ?: return SendResult.MissingChat
        if (!projected(record).canSend) return SendResult.Forbidden
        if (!sends.add(chatId)) return SendResult.Failed
        val session = epoch
        return try {
            val message = client.sendText(chatId, text, replyTo)
            currentCoroutineContext().ensureActive()
            if (!live || session != epoch) SendResult.MissingChat
            else { put(message); publishMessages(); SendResult.Sent }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if ((failure as? MessagingException)?.failure == MessagingFailure.Network) SendResult.Uncertain else SendResult.Failed }
        finally { if (session == epoch) sends.remove(chatId) }
    }
    override suspend fun sendPhotos(chatId: Long, photos: List<com.vector.core.media.UploadPhoto>, caption: String, album: Boolean, replyTo: Long?): com.vector.core.media.PhotoSendResult {
        fun result(value: SendResult, count: Int = 0) = com.vector.core.media.PhotoSendResult(count, value)
        if (!live) return result(SendResult.Unsupported)
        if (photos.size !in 1..100 || photos.any { (!it.document && (it.width <= 0 || it.height <= 0)) || it.path.isBlank() } ||
            photos.any { it.document != photos.first().document }) return result(SendResult.Empty)
        if (caption.length > 1024) return result(SendResult.TooLong)
        if (records[chatId] == null) return result(SendResult.MissingChat)
        if (!sends.add(chatId)) return result(SendResult.Failed)
        val session = epoch
        var accepted = 0
        return try {
            if (!client.photoPermission(chatId, photos.first().document)) return result(SendResult.Forbidden)
            val batches = if (album && !photos.first().document) photos.chunked(10) else photos.map { listOf(it) }
            for (batch in batches) {
                currentCoroutineContext().ensureActive()
                if (!live || session != epoch) return result(SendResult.MissingChat, accepted)
                val sent = client.sendPhotos(chatId, batch, if (accepted == 0) caption else "", if (accepted == 0) replyTo else null)
                currentCoroutineContext().ensureActive()
                if (!live || session != epoch) return result(SendResult.MissingChat, accepted)
                sent.forEach { put(it) }; publishMessages()
                accepted += batch.size
            }
            result(SendResult.Sent, accepted)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { result(when ((failure as? MessagingException)?.failure) {
            MessagingFailure.Network -> SendResult.Uncertain
            MessagingFailure.Forbidden -> SendResult.Forbidden
            else -> SendResult.Failed
        }, accepted) }
        finally { if (session == epoch) sends.remove(chatId) }
    }
    override fun cancelPhotoSend(chatId: Long, messageId: Long) {
        val message = window[messageId]?.takeIf { it.chatId == chatId } ?: return
        if (message.media?.kind !in listOf(com.vector.core.media.MediaKind.Photo, com.vector.core.media.MediaKind.Document) || message.delivery !in listOf(Delivery.Sending, Delivery.Failed)) return
        val key = chatId to messageId
        if (!photoCancellations.add(key)) return
        val session = epoch
        sessionScope?.launch {
            try {
                client.cancelPhotoSend(chatId, messageId)
                ensureActive()
                if (session == epoch && active == chatId) {
                    revision++; versions[messageId] = revision
                    window.remove(messageId)
                    replaced += messageId
                    if (replaced.size > WINDOW * 3) replaced.remove(replaced.first())
                    publishMessages()
                }
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session == epoch) reactionErrorState.value = "Unable to cancel this upload. Check its current sending state." }
            finally { if (session == epoch) photoCancellations.remove(key) }
        }
    }
    override suspend fun editText(chatId: Long, messageId: Long, text: String): SendResult {
        if (!live) return demo.editText(chatId, messageId, text)
        if (text.isBlank()) return SendResult.Empty
        if (text.length > 4096) return SendResult.TooLong
        val original = window[messageId]?.takeIf { it.chatId == chatId } ?: return SendResult.MissingChat
        if (!original.outgoing || original.media != null || original.attachment != null || original.restricted ||
            original.delivery !in listOf(Delivery.Sent, Delivery.Read)) return SendResult.Forbidden
        if (text == original.text) return SendResult.Sent
        if (!sends.add(chatId)) return SendResult.Failed
        val session = epoch
        return try {
            val message = client.editText(chatId, messageId, text)
            currentCoroutineContext().ensureActive()
            if (!live || session != epoch) SendResult.MissingChat
            else { put(message); publishMessages(); SendResult.Sent }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if ((failure as? MessagingException)?.failure == MessagingFailure.Forbidden) SendResult.Forbidden else SendResult.Failed }
        finally { if (session == epoch) sends.remove(chatId) }
    }
    override suspend fun sendVoice(chatId: Long, clip: com.vector.core.audio.VoiceClip, replyTo: Long?): SendResult {
        if (!live) return SendResult.Unsupported
        if (clip.duration !in 1..600 || clip.path.isBlank() || clip.waveform.size > 100) return SendResult.Empty
        val record = records[chatId] ?: return SendResult.MissingChat
        if (!projected(record).canSend) return SendResult.Forbidden
        if (!sends.add(chatId)) return SendResult.Failed
        val session = epoch
        return try {
            val message = client.sendVoice(chatId, clip, replyTo)
            currentCoroutineContext().ensureActive()
            if (!live || session != epoch) SendResult.MissingChat
            else { put(message); publishMessages(); SendResult.Sent }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { if ((failure as? MessagingException)?.failure == MessagingFailure.Network) SendResult.Uncertain else SendResult.Failed }
        finally { if (session == epoch) sends.remove(chatId) }
    }
    override fun retry(chatId: Long, messageId: Long) {
        val message = window[messageId] ?: return
        if (!live || !message.canRetry || message.delivery != Delivery.Failed || message.retryAt > System.currentTimeMillis() || !retries.add(messageId)) return
        sessionScope?.launch {
            try { val result = client.resend(chatId, messageId); ensureActive(); result.forEach { put(it) }; publishMessages() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { historyState.update { it.copy(error = "Unable to retry this message. Try again later.") } }
            finally { retries.remove(messageId) }
        }
    }
    override fun viewMessages(chatId: Long, messageIds: List<Long>) {
        if (!live || active != chatId) return
        val ids = messageIds.filter { it in window && it !in visibleReads && window[it]?.delivery !in listOf(Delivery.Sending, Delivery.Failed) }.take(100)
        if (ids.isEmpty()) return
        visibleReads += ids
        sessionScope?.launch {
            try { client.view(chatId, ids) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { visibleReads.removeAll(ids.toSet()) }
        }
    }
    override fun avatar(chatId: Long) {
        val photo = records[chatId]?.let(::projected)?.photo ?: return
        if (!live || photo.localPath != null || !avatarRequests.add(photo.fileId)) return
        sessionScope?.launch {
            try {
                val file = client.downloadAvatar(photo.fileId)
                if (file.localPath != null) reduce(ChatUpdate.File(file))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { avatarRequests.remove(photo.fileId) }
        }
    }
    override fun latest(chatId: Long) { if (live) { closeConversation(chatId); openConversation(chatId) } }
    override fun markRead(chatId: Long) { if (!live) demo.markRead(chatId) }
    override fun toggleReaction(chatId: Long, messageId: Long, reaction: String) {
        if (!live) { demo.toggleReaction(chatId, messageId, reaction); return }
        val message = window[messageId]?.takeIf { it.chatId == chatId } ?: return
        if (message.restricted || message.isService || message.delivery in listOf(Delivery.Sending, Delivery.Failed)) return
        val key = chatId to messageId
        if (!reactionRequests.add(key)) return
        reactionErrorState.value = null
        val session = epoch
        sessionScope?.launch {
            try {
                val version = versions[messageId]
                val confirmed = client.toggleReaction(chatId, messageId, reaction)
                ensureActive()
                // An incremental update arriving during the request takes priority over its snapshot.
                if (session == epoch && active == chatId && versions[messageId] == version) {
                    window[messageId]?.let { window[messageId] = it.copy(reactions = confirmed) }; publishMessages()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session == epoch) reactionErrorState.value = "This reaction is unavailable or could not be updated." }
            finally { if (session == epoch) reactionRequests.remove(key) }
        }
    }
    override fun toggleArchive(chatId: Long) = action(chatId, { demo.toggleArchive(it) }) { record -> client.archive(chatId, !record.chat.archived) }
    override fun togglePin(chatId: Long) = action(chatId, { demo.togglePin(it) }) { record -> client.pin(chatId, record.chat.archived, !record.chat.pinned) }
    private fun action(id: Long, demoAction: (Long) -> Unit, block: suspend (TelegramChat) -> Unit) {
        if (!live) { demoAction(id); return }
        val record = records[id] ?: return
        // Read the currently visible list flags, which are derived from TDLib positions.
        val visible = chats.value.find { it.id == id } ?: return
        sessionScope?.launch {
            try { block(record.copy(chat = visible)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { statusState.update { it.copy(error = "Telegram could not update this chat.") } }
        }
    }
    companion object { const val PAGE = 50; const val WINDOW = 400 }
}
