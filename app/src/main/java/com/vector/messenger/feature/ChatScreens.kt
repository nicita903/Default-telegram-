package com.vector.messenger.feature

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import com.vector.messenger.design.DesignIcon as Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.navigation.gesture.PullSearchState
import com.vector.messenger.navigation.gesture.pullToSearch

@Composable
fun ChatsScreen(viewModel: ChatsViewModel, onSelect: (Long) -> Unit, onSearch: () -> Unit = {}, listState: LazyListState = rememberLazyListState()) {
    val chats by viewModel.filteredChats.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val filter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    var selectedChat by rememberSaveable(status.session) { mutableStateOf<Long?>(null) }
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    val pull = remember { PullSearchState() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val strength = VectorMotionSpec.strength(appearance)
    val offset = rememberInteractiveOffset({ pull.distance * .45f * strength }, { pull.eligible })
    val showingPull by remember { derivedStateOf { pull.distance > 0 } }
    LaunchedEffect(listState, filter, status.session, status.loading) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .collect { last -> if (status.live && !status.loading && status.error == null && last >= 0 && last >= listState.layoutInfo.totalItemsCount - 5) viewModel.moreChats() }
    }
    Column(Modifier.fillMaxSize()) {

        // Chats header: the New chat control lives in the app header now, so this row is the count.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${chats.size} conversations",
                style = MaterialTheme.typography.labelSmall,
                color = colors.secondary,
                modifier = Modifier.weight(1f)
            )
        }

        status.error?.let { DefaultAction(it, viewModel::moreChats, Modifier.padding(horizontal = 20.dp)) }
        if (chats.isEmpty()) {
            EmptyState(if (status.live && status.loading) "Loading Telegram chats…" else "No conversations", if (status.live) "Your Telegram chats appear here." else "Try a different search or folder.")
            if (status.live && !status.loading) DefaultAction("Load chats", viewModel::moreChats)
        }
        Box(Modifier.weight(1f).fillMaxWidth().pullToSearch(listState, pull, appearance.pullSearch, appearance.pullDistance, appearance.haptics, onSearch)
            .semantics { customActions = listOf(CustomAccessibilityAction("Open Chat Search") { onSearch(); true }) }) {
        if (showingPull) Icon(Icons.Default.Search, "Release to search", Modifier.align(Alignment.TopCenter).padding(top = 8.dp).size(22.dp)
            .graphicsLayer { alpha = (pull.distance / with(density) { appearance.pullDistance.dp.toPx() }).coerceIn(0f, 1f) }, tint = colors.accent)
        LazyColumn(Modifier.fillMaxSize().graphicsLayer { translationY = if (pull.distance > 0) pull.distance * .45f * strength else offset.value }.testTag("chat_list"), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
            items(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                if (com.vector.messenger.feature.avatar.LocalAvatarViewModel.current == null) LaunchedEffect(chat.id, chat.photo?.fileId) { viewModel.avatar(chat.id) }
                ChatRow(chat, selectedChat == chat.id,
                    { selectedChat = chat.id; onSelect(chat.id) }, { viewModel.pin(chat.id) }, { viewModel.archive(chat.id) })
            }
            if (status.live && !status.loading && (if (filter == ChatFilter.Archived) status.archiveMore else status.mainMore))
                item(key = "more_chats") { DefaultAction("Load more chats", viewModel::moreChats, Modifier.padding(16.dp)) }
        }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, selected: Boolean, onClick: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val colors = DefaultDesign.colors
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val fill by animateColorAsState(if (pressed) colors.raised.copy(alpha = .55f) else if (selected) colors.panel.copy(alpha = .35f) else Color.Transparent, VectorMotionSpec.feedback(LocalAppearance.current), label = "Conversation selection")
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(VectorMotionSpec.RowPress) else 1f, VectorMotionSpec.press(pressed, LocalAppearance.current), label = "Conversation compression")
    Row(Modifier.fillMaxWidth().graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .background(fill).semantics { this.selected = selected }
        .clickable(interactionSource = interaction, indication = null, onClick = onClick).heightIn(min = if (LocalAppearance.current.compact) 72.dp else 88.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(38.dp).background(if (selected) colors.accent else Color.Transparent))
        Spacer(Modifier.width(18.dp))
        TelegramAvatar(chat.title, chat.photo, 48.dp, chat.userId ?: chat.id, user = chat.userId != null)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(chat.title, fontWeight = if (chat.unread > 0) FontWeight.SemiBold else FontWeight.Medium,
                    color = colors.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (chat.unread > 0) colors.active else colors.secondary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val prefix = when (chat.kind) { ChatKind.Contact -> ""; ChatKind.Group -> "Group · "; ChatKind.Channel -> "Channel · " }
                Text(prefix + chat.preview, color = colors.secondary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (chat.pinned) Icon(VectorIcons.Pin, "Pinned conversation", Modifier.size(13.dp), tint = colors.secondary)
                if (chat.unread > 0) Box(Modifier.background(colors.accent, DefaultDesign.avatarShape).defaultMinSize(24.dp, 22.dp)
                    .semantics { contentDescription = "${chat.unread} unread messages" }.padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                    Text(chat.unread.toString(), style = MaterialTheme.typography.labelSmall, color = colors.onAccent)
                }
            }
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 2.dp)
                .excludeModuleGestures()
        ) {
            DefaultIconControl(
                icon = Icons.Default.MoreVert,
                description = "Actions for ${chat.title}",
                onClick = { menu = true },
                quiet = true
            )

            DefaultActionMenu(
                open = menu,
                onDismiss = { menu = false }
            ) {
                DefaultAction(
                    text = if (chat.pinned) "Unpin" else "Pin",
                    onClick = {
                        onPin()
                        menu = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = VectorIcons.Pin
                )

                DefaultAction(
                    text = if (chat.archived) "Unarchive" else "Archive",
                    onClick = {
                        onArchive()
                        menu = false
                    },
                    modifier = Modifier.fillMaxWidth(),
                    icon = VectorIcons.Download
                )
            }
        }
    }
    DefaultRule(Modifier.padding(start = 80.dp, end = 20.dp))
}

@Composable
fun ContactsScreen(contacts: List<Chat>, onSelect: (Long) -> Unit) {
    val colors = DefaultDesign.colors
    LazyColumn {
        items(contacts, key = { it.id }, contentType = { "contact" }) { contact ->
            Row(Modifier.fillMaxWidth().clickable { onSelect(contact.id) }.heightIn(min = 76.dp).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TelegramAvatar(contact.title, contact.photo, 44.dp, contact.userId ?: contact.id, user = contact.userId != null)
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(contact.title, style = MaterialTheme.typography.titleMedium, color = colors.text)
                    Text("Sample contact", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                }
            }
            DefaultRule(Modifier.padding(start = 76.dp))
        }
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = DefaultDesign.colors.text)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = DefaultDesign.colors.secondary)
    }
}
