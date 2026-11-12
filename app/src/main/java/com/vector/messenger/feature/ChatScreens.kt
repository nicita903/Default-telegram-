package com.vector.messenger.feature

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.*
import com.vector.messenger.design.ChatAvatar
import com.vector.messenger.design.VectorIcons
import com.vector.messenger.navigation.gesture.excludeModuleGestures

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsScreen(viewModel: ChatsViewModel, onSelect: (Long) -> Unit) {
    val chats by viewModel.filteredChats.collectAsStateWithLifecycle()
    val allChats by viewModel.chats.collectAsStateWithLifecycle()
    val query by viewModel.search.collectAsStateWithLifecycle()
    val filter by viewModel.selectedFilter.collectAsStateWithLifecycle()
    var newMessage by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TextField(
                value = query, onValueChange = viewModel::search,
                placeholder = { Text("Search conversations") }, singleLine = true,
                leadingIcon = { Icon(Icons.Default.Search, null) },
                shape = CircleShape,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                ),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).excludeModuleGestures().testTag("chat_search"),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).excludeModuleGestures()) {
                ChatFilter.entries.forEach { folder ->
                    Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 80.dp)) {
                        Tab(selected = folder == filter, onClick = { viewModel.filter(folder) },
                            selectedContentColor = MaterialTheme.colorScheme.primary, unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = { Text(if (folder == ChatFilter.All) "All chats" else folder.name, maxLines = 1) })
                        Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(2.dp).background(if (folder == filter) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (chats.isEmpty()) EmptyState("No conversations", "Try a different search or folder.")
            LazyColumn(Modifier.weight(1f).testTag("chat_list"), contentPadding = PaddingValues(bottom = 88.dp)) {
                items(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                    ChatRow(chat, { onSelect(chat.id) }, { viewModel.pin(chat.id) }, { viewModel.archive(chat.id) })
                }
            }
        }
        FloatingActionButton(onClick = { newMessage = true }, shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp).excludeModuleGestures()) {
            Icon(Icons.Default.Edit, "New message")
        }
    }
    if (newMessage) ModalBottomSheet(onDismissRequest = { newMessage = false }) {
        Text("New message", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
        ContactsScreen(allChats.filter { it.isContact }) { newMessage = false; onSelect(it) }
    }
}

@Composable
private fun ChatRow(chat: Chat, onClick: () -> Unit, onPin: () -> Unit, onArchive: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatAvatar(chat.title)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(chat.title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val prefix = when (chat.kind) { ChatKind.Contact -> ""; ChatKind.Group -> "Group · "; ChatKind.Channel -> "Channel · " }
            Text(prefix + chat.preview, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(chat.time, style = MaterialTheme.typography.labelSmall, color = if (chat.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (chat.pinned) Icon(VectorIcons.Pin, "Pinned conversation", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                if (chat.unread > 0) Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary) {
                    Box(Modifier.defaultMinSize(22.dp, 22.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                        Text(chat.unread.toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary)
                    }
                }
            }
        }
        Box(Modifier.excludeModuleGestures()) {
            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Actions for ${chat.title}", modifier = Modifier.size(18.dp)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text(if (chat.pinned) "Unpin" else "Pin") }, onClick = { onPin(); menu = false })
                DropdownMenuItem(text = { Text(if (chat.archived) "Unarchive" else "Archive") }, onClick = { onArchive(); menu = false })
            }
        }
    }
    HorizontalDivider(Modifier.padding(start = 84.dp), color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
}

@Composable
fun ContactsScreen(contacts: List<Chat>, onSelect: (Long) -> Unit) {
    LazyColumn {
        items(contacts, key = { it.id }) { contact ->
            ListItem(leadingContent = { ChatAvatar(contact.title, 44.dp) }, headlineContent = { Text(contact.title) }, supportingContent = { Text("Sample contact") }, modifier = Modifier.clickable { onSelect(contact.id) }, colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background))
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
