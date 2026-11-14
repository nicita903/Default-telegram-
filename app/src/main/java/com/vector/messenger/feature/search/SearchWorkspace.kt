package com.vector.messenger.feature.search

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.ChatKind
import com.vector.messenger.design.*
import com.vector.messenger.feature.ContactsScreen
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.navigation.gesture.excludeModuleGestures

private val LinkPattern = Regex("https?://\\S+")

@Composable
fun SearchWorkspace(viewModel: SearchViewModel, scope: SearchScope, blockedAttempt: Int, onScope: (SearchScope) -> Unit, onOpen: (Long) -> Unit, listState: LazyListState) {
    val appearance = LocalAppearance.current
    val colors = DefaultDesign.colors
    val chatQuery by viewModel.chatQuery.collectAsStateWithLifecycle()
    val musicQuery by viewModel.musicQuery.collectAsStateWithLifecycle()
    val query = if (scope == SearchScope.Chats) chatQuery else musicQuery
    val locked = searchLocked(query, appearance.autoLock)
    val bump = remember { Animatable(0f) }
    val distance = with(LocalDensity.current) { 8.dp.toPx() } * if (scope == SearchScope.Chats) 1 else -1
    var highlight by remember { mutableStateOf(false) }
    var lastBlocked by remember { mutableIntStateOf(blockedAttempt) }
    LaunchedEffect(blockedAttempt, locked, appearance.reducedMotion, appearance.elastic, appearance.intensity, appearance.speed) {
        if (blockedAttempt == lastBlocked || !locked || !appearance.elastic) {
            lastBlocked = blockedAttempt
            bump.snapTo(0f); highlight = false
            return@LaunchedEffect
        }
        lastBlocked = blockedAttempt
        highlight = true
        try {
            if (appearance.elastic && VectorMotionSpec.strength(appearance) > 0f) {
                bump.animateTo(distance * VectorMotionSpec.strength(appearance), VectorMotionSpec.lockPeak(appearance))
                bump.animateTo(0f, VectorMotionSpec.settle(appearance))
            } else kotlinx.coroutines.delay(VectorMotionSpec.highlightMillis(appearance))
        } finally { highlight = false }
    }
    val lockColor by animateColorAsState(if (highlight) colors.accent else colors.secondary, VectorMotionSpec.feedback(appearance), label = "Search lock feedback")
    Column(Modifier.fillMaxSize().background(if (scope == SearchScope.Chats) colors.base else colors.panel)
        .graphicsLayer { translationX = bump.value }.imePadding().testTag("search_workspace")) {
        Row(Modifier.fillMaxWidth().searchRegion(SearchRegionKind.Header).padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (scope == SearchScope.Chats) "Chat Search" else "Music Search", color = colors.text,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("search_scope").semantics { heading(); stateDescription = if (locked) "Locked" else "Unlocked" })
            AnimatedVisibility(locked && appearance.lockVisible, enter = VectorMotionSpec.lockEnter(appearance), exit = VectorMotionSpec.lockExit(appearance)) {
                Icon(Icons.Default.Lock, "Search context locked", Modifier.padding(start = 6.dp).size(12.dp), tint = lockColor)
            }
            Spacer(Modifier.weight(1f))
            // Explicit alternative to the fast gesture, also useful for touch exploration.
            DefaultIconControl(if (scope == SearchScope.Chats) VectorIcons.Music else VectorIcons.Chat,
                if (scope == SearchScope.Chats) "Switch to Music Search" else "Switch to Chat Search", { onScope(if (scope == SearchScope.Chats) SearchScope.Music else SearchScope.Chats) }, Modifier.searchRegion(SearchRegionKind.Excluded), quiet = true)
        }
        DefaultInput(query, { viewModel.query(scope, it) }, if (scope == SearchScope.Chats) "Search conversations" else "Search local music",
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).excludeModuleGestures().searchRegion(SearchRegionKind.Excluded).testTag("search_input"), leadingIcon = Icons.Default.Search,
            trailing = if (query.isNotEmpty()) ({ DefaultIconControl(Icons.Default.Close, "Clear search", { viewModel.query(scope, "") }, quiet = true) }) else null,
            surface = if (scope == SearchScope.Music) colors.raised else colors.panel)
        Spacer(Modifier.height(8.dp))
        if (scope == SearchScope.Chats) ChatSearchResults(viewModel, query, listState, onOpen)
        else MusicSearchResults(viewModel, query, listState)
    }
}

@Composable
private fun ChatSearchResults(viewModel: SearchViewModel, query: String, listState: LazyListState, onOpen: (Long) -> Unit) {
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf("All") }
    var create by remember { mutableStateOf<String?>(null) }
    val matchingChats = remember(chats, query) { chats.filter { it.title.contains(query.trim(), true) } }
    val matchingMessages = remember(messages, query, category) { messages.filter {
        (query.isNotBlank() || category in listOf("Media", "Files", "Links")) &&
            (it.text.contains(query.trim(), true) || it.attachment?.name?.contains(query.trim(), true) == true) && when (category) {
                "Media" -> it.attachment?.mimeType?.let { mime -> mime.startsWith("image/") || mime.startsWith("video/") } == true
                "Files" -> it.attachment != null
                "Links" -> LinkPattern.containsMatchIn(it.text)
                else -> true
            }
    } }
    SearchChoices(listOf("All", "People", "Conversations", "Messages", "Media", "Files", "Links"), category) { category = it }
    LazyColumn(Modifier.fillMaxSize().searchRegion(SearchRegionKind.Results).testTag("search_results"), state = listState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item { SearchCaption(if (status.live) "Loaded Telegram chats and messages" else "Demo repository · not synchronized") }
        if (query.isEmpty()) {
            if (recents.isNotEmpty()) {
                item { Row(verticalAlignment = Alignment.CenterVertically) { Text("Recent searches", Modifier.weight(1f), color = DefaultDesign.colors.secondary); DefaultIconControl(Icons.Default.Close, "Clear recent searches", viewModel::clearRecent, Modifier.searchRegion(SearchRegionKind.Excluded), quiet = true) } }
                items(recents, key = { "recent:$it" }) { recent -> SearchResultRow(recent, "Recent search", Icons.Default.Search) { viewModel.query(SearchScope.Chats, recent) } }
            }
            item { Row(Modifier.fillMaxWidth().excludeModuleGestures().searchRegion(SearchRegionKind.Excluded), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("New chat", "New group", "New channel").forEach { title -> Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    DefaultIconControl(if (title == "New chat") Icons.Default.Person else VectorIcons.Chat, title, { create = title })
                    Text(title, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                } }
            } }
        }
        if (category in listOf("All", "People", "Conversations")) {
            items(matchingChats.filter { category != "People" || it.kind == ChatKind.Contact }, key = { "chat:${it.id}" }) { chat ->
                SearchResultRow(chat.title, if (chat.kind == ChatKind.Contact) "Person · ${chat.preview}" else "${chat.kind} · ${chat.preview}", avatar = chat.title) { viewModel.rememberQuery(query); onOpen(chat.id) }
            }
        }
        if (category in listOf("All", "Messages", "Media", "Files", "Links")) {
            items(matchingMessages, key = { "message:${it.id}" }) { message ->
                SearchResultRow(message.text.ifEmpty { message.attachment?.name ?: "Attachment" }, chats.find { it.id == message.chatId }?.title.orEmpty(), VectorIcons.Chat) { viewModel.rememberQuery(query); onOpen(message.chatId) }
            }
        }
        if (matchingChats.isEmpty() && matchingMessages.isEmpty()) item { SearchCaption("No results") }
    }
    create?.let { title -> DefaultDialog(title, { create = null }) {
        if (title == "New chat") {
            Text(if (status.live) "Choose a Telegram conversation" else "Choose a sample contact", color = DefaultDesign.colors.secondary)
            Box(Modifier.heightIn(max = 320.dp)) { ContactsScreen(chats.filter { it.isContact }) { create = null; onOpen(it) } }
        } else Text("${title.removePrefix("New ").replaceFirstChar { it.uppercase() }} creation is not supported in this phase.", color = DefaultDesign.colors.secondary)
        DefaultAction("Done", { create = null }, Modifier.fillMaxWidth())
    } }
}

@Composable
private fun MusicSearchResults(viewModel: SearchViewModel, query: String, listState: LazyListState) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.refreshMusic() }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshMusic() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var category by rememberSaveable { mutableStateOf("Songs") }
    var collection by rememberSaveable { mutableStateOf<String?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }
    SearchChoices(listOf("Songs", "Albums", "Artists", "Playlists", "Recently played", "Recently added", "Telegram music"), category) { category = it; collection = null }
    val songs = remember(library, query, category, collection) {
        library.songs.filter { song ->
            (song.title.contains(query.trim(), true) || song.artist.contains(query.trim(), true) || song.album.contains(query.trim(), true)) && when (category) {
                "Albums" -> collection == song.album
                "Artists" -> collection == song.artist
                "Playlists" -> library.playlists.find { it.id.toString() == collection }?.songIds?.contains(song.id) == true
                "Recently played", "Telegram music" -> false
                else -> true
            }
        }.let { if (category == "Recently added") it.sortedByDescending { song -> song.added }.take(50) else it }
    }
    LazyColumn(Modifier.fillMaxSize().searchRegion(SearchRegionKind.Results).testTag("music_results"), state = listState, contentPadding = PaddingValues(20.dp)) {
        item { SearchCaption("On this device") }
        if (!library.permission) item {
            Text("Allow audio access to search your device library.", color = DefaultDesign.colors.secondary, modifier = Modifier.padding(vertical = 12.dp))
            DefaultAction("Allow audio access", { permission.launch(MusicLibraryRepository.permission) }, Modifier.searchRegion(SearchRegionKind.Excluded))
        }
        library.error?.let { item { SearchCaption(it) } }
        openError?.let { item { SearchCaption(it) } }
        if (collection != null) item { DefaultAction("All ${category.lowercase()}", { collection = null }, Modifier.searchRegion(SearchRegionKind.Excluded), icon = Icons.AutoMirrored.Filled.ArrowBack) }
        if (library.permission && collection == null && category in listOf("Albums", "Artists", "Playlists")) {
            val names = when (category) {
                "Albums" -> library.songs.map { it.album }.distinct().map { it to it }
                "Artists" -> library.songs.map { it.artist }.distinct().map { it to it }
                else -> library.playlists.map { it.id.toString() to it.name }
            }.filter { it.second.contains(query.trim(), true) }
            items(names, key = { "collection:${it.first}" }) { (id, name) -> SearchResultRow(name, category.dropLast(1), VectorIcons.Music) { collection = id } }
            if (names.isEmpty()) item { SearchCaption("No ${category.lowercase()} found") }
        } else {
            items(songs, key = { it.id }) { song -> SearchResultRow(song.title, "${song.artist} · ${song.album}", VectorIcons.Music) {
                try { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(song.uri.toUri(), "audio/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                catch (_: ActivityNotFoundException) { openError = "No audio player is available." }
                catch (_: SecurityException) { openError = "Audio access changed. Allow access again."; viewModel.refreshMusic() }
            } }
            if (library.permission && songs.isEmpty()) item { SearchCaption(when (category) {
                "Recently played" -> "No playback history. This client does not track external player history."
                "Telegram music" -> "No imported Telegram music. Telegram audio importing is not connected yet."
                else -> "No local songs found"
            }) }
        }
    }
}

@Composable
private fun SearchCaption(text: String) { Text(text, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(vertical = 12.dp)) }

@Composable
private fun SearchChoices(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).excludeModuleGestures().searchRegion(SearchRegionKind.Excluded).padding(horizontal = 12.dp)) {
        options.forEach { choice -> Text(choice, color = if (selected == choice) DefaultDesign.colors.accent else DefaultDesign.colors.secondary,
            style = MaterialTheme.typography.labelLarge, modifier = Modifier.heightIn(min = 48.dp).semantics { this.selected = selected == choice }.clickable(role = Role.Tab) { onSelect(choice) }.padding(12.dp)) }
    }
}

@Composable
private fun SearchResultRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Default.Person, avatar: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null, onClick = onClick)
        .heightIn(min = if (LocalAppearance.current.compact) 64.dp else 80.dp).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (avatar != null) ChatAvatar(avatar, 40.dp)
        else Box(Modifier.size(40.dp).background(DefaultDesign.colors.raised, androidx.compose.foundation.shape.CircleShape), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(18.dp), tint = DefaultDesign.colors.accent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
