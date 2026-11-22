package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.Chat
import com.vector.messenger.design.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures

/** Only actions implemented in this checkout are offered; no placeholder call/privacy controls. */
@Composable
fun ConversationOptions(chat: Chat, viewModel: ChatsViewModel) {
    var open by remember(chat.id) { mutableStateOf(false) }
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val offset = with(LocalDensity.current) { 48.dp.roundToPx() }
    Box(Modifier.excludeModuleGestures()) {
        DefaultIconControl(Icons.Default.MoreVert, "Chat options", { open = true }, quiet = true)
        DefaultActionMenu(open, { open = false }, IntOffset(0, offset)) {
            fun choose(name: String) { open = false; viewModel.showChatTool(chat.id, name) }
            DefaultAction("Conversation info", { choose("info") }, Modifier.fillMaxWidth())
            DefaultAction("Search in chat", { choose("search") }, Modifier.fillMaxWidth())
            if (messages.any { it.chatId == chat.id && it.media != null && !it.restricted })
                DefaultAction("Shared media", { choose("media") }, Modifier.fillMaxWidth())
            DefaultAction(if (chat.pinned) "Unpin chat" else "Pin chat", { open = false; viewModel.pin(chat.id) }, Modifier.fillMaxWidth())
            DefaultAction(if (chat.archived) "Unarchive chat" else "Archive chat", { open = false; viewModel.archive(chat.id) }, Modifier.fillMaxWidth())
        }
    }
}
