package com.vector.messenger.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*

@Composable
fun ChatsScreen(viewModel: ChatsViewModel, onSelect: (Long) -> Unit) {
    val chats by viewModel.filteredChats.collectAsStateWithLifecycle()
    val query by viewModel.search.collectAsStateWithLifecycle()
    val filter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query, onValueChange = viewModel::search,
            label = { Text("Search conversations") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
        )
        Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChatFilter.entries.forEach {
                FilterChip(selected = it == filter, onClick = { viewModel.filter(it) }, label = { Text(it.name) })
            }
        }
        if (chats.isEmpty()) EmptyState("No conversations", "Try a different search or filter.")
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            items(chats, key = { it.id }) { chat ->
                ChatRow(chat, { onSelect(chat.id) }, { viewModel.pin(chat.id) }, { viewModel.archive(chat.id) })
            }
        }
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                Text(chat.title.take(1), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(chat.title, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(chat.preview, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (chat.pinned) Text("Pinned", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
            Text(chat.time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (chat.unread > 0) Badge(Modifier.padding(top = 6.dp)) { Text(chat.unread.toString()) }
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${chat.title}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (chat.pinned) "Unpin" else "Pin") }, onClick = { onPin(); menu = false })
                DropdownMenuItem(text = { Text(if (chat.archived) "Unarchive" else "Archive") }, onClick = { onArchive(); menu = false })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 82.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun ConversationScreen(chatId: Long, viewModel: ChatsViewModel) {
    val allMessages by viewModel.messages.collectAsStateWithLifecycle()
    val messages = remember(allMessages, chatId) { allMessages.filter { it.chatId == chatId } }
    var draft by rememberSaveable(chatId) { mutableStateOf("") }
    val error by viewModel.error.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) listState.scrollToItem(messages.lastIndex) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(state = listState, modifier = Modifier.weight(1f), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(messages, key = { it.id }) { message ->
                val outgoing = message.delivery == Delivery.LocalDemo
                Row(Modifier.fillMaxWidth(), horizontalArrangement = if (outgoing) Arrangement.End else Arrangement.Start) {
                    Column(Modifier.widthIn(max = 320.dp).background(
                        if (outgoing) MaterialTheme.colorScheme.primary.copy(alpha = .13f) else MaterialTheme.colorScheme.surfaceContainer,
                        RoundedCornerShape(12.dp),
                    ).padding(12.dp)) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        Text("${message.time}${if (outgoing) " · Local demo" else ""}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.End).padding(top = 4.dp))
                    }
                }
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Demo message") }, maxLines = 5, modifier = Modifier.weight(1f))
            IconButton(enabled = draft.isNotBlank(), onClick = { viewModel.send(chatId, draft) { draft = "" } }) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send demo message", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
fun ContactsScreen(contacts: List<Chat>, onSelect: (Long) -> Unit) {
    LazyColumn {
        items(contacts, key = { it.id }) { contact ->
            ListItem(headlineContent = { Text(contact.title) }, supportingContent = { Text("Sample contact") }, modifier = Modifier.clickable { onSelect(contact.id) })
        }
    }
}

@Composable
fun EmptyState(title: String, description: String) {
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
