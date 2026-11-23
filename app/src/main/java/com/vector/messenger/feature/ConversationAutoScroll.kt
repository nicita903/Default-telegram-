package com.vector.messenger.feature

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.vector.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ConversationBookmark(val anchor: Long?, val offset: Int, val nearLatest: Boolean)
internal class ConversationScroll {
    var pending by mutableIntStateOf(0)
    var restoring by mutableStateOf(true)
    var latest: () -> Unit = {}
}

/** Reverse layout: index zero is newest, and pixel offsets/known row heights determine distance.
 * Arrival.followLatest is sampled by the repository BEFORE insertion; post-insert indices never
 * decide whether an incoming message is allowed to move the reader.
 */
@Composable
internal fun rememberConversationScroll(chatId: Long, session: Long, list: LazyListState, vm: ChatsViewModel,
    messages: List<Message>, history: HistoryState): ConversationScroll {
    val control = remember(chatId, session) { ConversationScroll() }
    val bookmark = remember(chatId, session) { vm.scrollBookmarks[session to chatId] }
    val heights = remember(chatId, session) { mutableMapOf<Long, Int>() }
    val threshold = with(LocalDensity.current) { 80.dp.toPx() }
    val scope = rememberCoroutineScope()
    val currentMessages by rememberUpdatedState(messages)
    val currentHistory by rememberUpdatedState(history)
    val appearance by rememberUpdatedState(LocalAppearance.current)
    val pendingIds = remember(chatId, session) { linkedSetOf<Long>() }
    var scrollJob by remember(chatId, session) { mutableStateOf<Job?>(null) }
    var scrollRequested by remember(chatId, session) { mutableStateOf(false) }
    var explicitRequest by remember(chatId, session) { mutableStateOf(false) }
    var near by remember(chatId, session) { mutableStateOf(bookmark?.nearLatest ?: true) }
    fun startLatest(explicit: Boolean) {
        if (!explicit && list.isScrollInProgress) return
        if (explicit && currentHistory.chatId == chatId && !currentHistory.atLatest) vm.latest(chatId)
        scrollRequested = true; explicitRequest = explicitRequest || explicit
    }
    control.latest = { startLatest(true) }
    // Initial fresh chats are already index zero. On return restore by message ID, not an index
    // potentially shifted by newer messages. History loading remains bounded by the existing window.
    val messageIds = remember(messages) { messages.map { it.id } }
    LaunchedEffect(chatId, session, messageIds, history.loading, history.hasMore) {
        if (!control.restoring) return@LaunchedEffect
        if (messages.isEmpty()) return@LaunchedEffect
        val index = bookmark?.anchor?.let { anchor -> messages.asReversed().indexOfFirst { it.id == anchor } } ?: -1
        if (index >= 0) {
            list.requestScrollToItem(index, bookmark?.offset ?: 0)
            control.restoring = false
        } else if (bookmark?.anchor != null && !bookmark.nearLatest && messages.minOf { it.id } > bookmark.anchor && history.hasMore && messages.size < com.vector.core.messaging.SessionChatRepository.WINDOW) {
            if (!history.loading) vm.older(chatId)
        } else {
            list.requestScrollToItem(0)
            control.restoring = false
            if (!history.atLatest) vm.latest(chatId)
        }
    }
    LaunchedEffect(list, chatId, session, threshold) {
        snapshotFlow { Triple(list.layoutInfo, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset, list.isScrollInProgress) }.collect { (layout, position, scrolling) ->
            layout.visibleItemsInfo.forEach { item -> (item.key as? Long)?.let { heights[it] = item.size } }
            while (heights.size > 160) heights.remove(heights.keys.first())
            val ordered = currentMessages.asReversed()
            var distance = position.second.toFloat()
            if (position.first > 0) {
                for (index in 0 until position.first) {
                    val height = ordered.getOrNull(index)?.id?.let { heights[it] }
                    if (height == null) { distance = Float.POSITIVE_INFINITY; break }
                    distance += height
                    if (distance > threshold) break
                }
            }
            near = distance <= threshold
            val realLatest = near && currentHistory.atLatest
            vm.viewport(chatId, realLatest && !control.restoring, scrolling)
            if (realLatest && !control.restoring) { pendingIds.clear(); control.pending = 0 }
            if (!control.restoring && ordered.isNotEmpty()) {
                val anchor = layout.visibleItemsInfo.firstOrNull { it.index == position.first }?.key as? Long
                if (anchor != null) {
                    vm.scrollBookmarks[session to chatId] = ConversationBookmark(anchor, position.second, realLatest)
                    while (vm.scrollBookmarks.size > 16) vm.scrollBookmarks.remove(vm.scrollBookmarks.keys.first())
                }
            }
        }
    }
    LaunchedEffect(chatId, session) {
        vm.arrivals.collect { arrival ->
            if (arrival.chatId != chatId || arrival.session != session) return@collect
            if (arrival.localSend) startLatest(true)
            else if (arrival.followLatest && !list.isScrollInProgress && !control.restoring) startLatest(false)
            else if (!arrival.outgoing && pendingIds.add(arrival.messageId)) {
                control.pending = (control.pending + 1).coerceAtMost(Int.MAX_VALUE - 1)
                while (pendingIds.size > 1200) pendingIds.remove(pendingIds.first())
            }
        }
    }
    LaunchedEffect(chatId, session, vm) { vm.submitted.collect { if (it == chatId) startLatest(true) } }
    // One cancellable scroll for coalesced arrivals. Property-only updates never set this flag.
    LaunchedEffect(scrollRequested, messages.firstOrNull()?.id, messages.lastOrNull()?.id, history.loading) {
        if (!scrollRequested || messages.isEmpty() || control.restoring || history.chatId == chatId && !history.atLatest) return@LaunchedEffect
        val explicit = explicitRequest
        scrollRequested = false; explicitRequest = false
        if (!explicit && list.isScrollInProgress) return@LaunchedEffect
        scrollJob?.cancel()
        scrollJob = scope.launch {
            try {
                if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0) return@launch
                if (com.vector.messenger.design.VectorMotionSpec.strength(appearance) == 0f || list.firstVisibleItemIndex > 8) list.scrollToItem(0)
                else {
                    val ordered = currentMessages.asReversed()
                    var distance = list.firstVisibleItemScrollOffset.toFloat()
                    var known = true
                    for (index in 0 until list.firstVisibleItemIndex) {
                        val height = ordered.getOrNull(index)?.id?.let { heights[it] }
                        if (height == null) { known = false; break }
                        distance += height
                    }
                    if (known) {
                        list.animateScrollBy(-distance, com.vector.messenger.design.VectorMotionSpec.transition(appearance))
                        list.scrollToItem(0)
                    } else list.animateScrollToItem(0)
                }
                pendingIds.clear(); control.pending = 0
            } catch (cancelled: CancellationException) { throw cancelled }
        }
    }
    DisposableEffect(chatId, session) { onDispose { scrollJob?.cancel() } }
    return control
}
