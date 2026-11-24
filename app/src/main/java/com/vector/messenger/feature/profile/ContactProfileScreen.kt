@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.vector.messenger.feature.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.audio.VoiceAttachment
import com.vector.messenger.feature.media.*
import com.vector.messenger.feature.video.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.telegram.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** A transient screen over the existing chat. Its own backdrop host blurs this profile for media overlays. */
@Composable
fun ContactProfileScreen(chat: Long, onClose: () -> Unit, onSearch: () -> Unit) {
    val vm = LocalContactProfileViewModel.current ?: return
    val media = LocalMediaViewModel.current ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val presence by vm.presence.collectAsStateWithLifecycle()
    val mediaState by media.state.collectAsStateWithLifecycle()
    val originalSession = remember { media.state.value.session }
    val pager = rememberPagerState { SharedCategory.entries.size }
    val scope = rememberCoroutineScope()
    val scrolls = remember { List(SharedCategory.entries.size) { LazyListState() } }
    var menu by remember { mutableStateOf(false) }
    var callsOpen by remember { mutableStateOf(false) }
    var detailsOpen by remember { mutableStateOf(false) }
    var selected by rememberSaveable(chat) { mutableStateOf<Long?>(null) }
    var viewerCategory by rememberSaveable(chat) { mutableStateOf(SharedCategory.Media.name) }
    val context = LocalContext.current
    val settings = LocalAppearance.current
    DisposableEffect(chat, vm) { vm.open(chat); onDispose { vm.close() } }
    LaunchedEffect(mediaState.session, mediaState.authenticated) { if (!mediaState.authenticated || mediaState.session != originalSession) onClose() }
    LaunchedEffect(pager.settledPage, state.chat) {
        if (state.chat == chat) {
            val category = SharedCategory.entries[pager.settledPage]
            if (category != SharedCategory.All && state.pages[category]?.loaded != true) vm.load(category)
        }
    }
    fun category(value: SharedCategory) { scope.launch { pager.animateScrollToPage(value.ordinal,
        animationSpec = VectorMotionSpec.transition(settings)) } }
    val detail = state.details
    fun openEntry(entry: SharedEntry, category: SharedCategory) { viewerCategory = category.name; selected = entry.message.id }
    DefaultFloatingOverlay(onDismiss = onClose) {
        FloatingBackdropHost {
            Box(Modifier.fillMaxSize().designCanvas(DefaultDesign.colors.canvas).safeDrawingPadding().excludeModuleGestures()) {
                HorizontalPager(state = pager, key = { SharedCategory.entries[it].name }, modifier = Modifier.fillMaxSize()) { pageIndex ->
                    val active = SharedCategory.entries[pageIndex]
                    val page = state.pages[active] ?: ContactCategoryState()
                    val list = scrolls[pageIndex]
                    LaunchedEffect(list, active, page.entries.size, page.more, page.loading, page.error) {
                        if (active == SharedCategory.All) return@LaunchedEffect
                        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }.collect { last ->
                            if (last >= list.layoutInfo.totalItemsCount - 4 && page.loaded && page.more && !page.loading && page.error == null) vm.load(active)
                        }
                    }
                    LazyColumn(state = list, contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
                        item(key = "profile", contentType = "profile") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                DefaultIconControl(Icons.AutoMirrored.Filled.ArrowBack, "Back to conversation", onClose, quiet = true)
                                DefaultIconControl(Icons.Default.MoreVert, "Contact actions", { menu = true }, quiet = true)
                            }
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                TelegramAvatar(detail?.name.orEmpty(), detail?.photo, 140.dp, detail?.user ?: chat, detail?.user != null)
                                if (detail != null) {
                                    Text(detail.name, style = MaterialTheme.typography.titleLarge, color = DefaultDesign.colors.text)
                                    Text(presence.text, style = MaterialTheme.typography.labelSmall,
                                        color = if (presence.online) DefaultDesign.colors.accent else DefaultDesign.colors.secondary)
                                    detail.username?.let { Text("@$it", color = DefaultDesign.colors.accent, style = MaterialTheme.typography.bodyMedium) }
                                    detail.bio?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
                                    if (detail.user == null) Text("${if (detail.kind == com.vector.core.ChatKind.Channel) "Channel" else "Group"} · shared library",
                                        color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                                } else if (state.loading) Text("Loading Telegram profile…", color = DefaultDesign.colors.secondary)
                                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    DefaultAction("MESSAGE", onClose, compact = true)
                                    DefaultAction("SEARCH", onSearch, compact = true)
                                    detail?.user?.let { com.vector.messenger.calling.VoiceCallButton(it) }
                                }
                            }
                            state.error?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = DefaultDesign.colors.secondary)
                                DefaultAction("Retry profile", vm::refresh, compact = true)
                            }
                        }
                        stickyHeader(key = "categories") {
                            Column(Modifier.fillMaxWidth().background(DefaultDesign.colors.canvas).padding(vertical = 8.dp)) {
                                Text("SHARED CONTENT", style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
                                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                    SharedCategory.entries.forEach { option ->
                                        val chosen = pager.targetPage == option.ordinal
                                        val color by animateColorAsState(if (chosen) DefaultDesign.colors.accent else DefaultDesign.colors.secondary,
                                            VectorMotionSpec.feedback(settings), label = "Shared category")
                                        Column(Modifier.clickable(role = Role.Tab) { category(option) }.semantics { this.selected = chosen }
                                            .padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(option.label, color = color, style = MaterialTheme.typography.bodyMedium)
                                            Spacer(Modifier.height(8.dp))
                                            Box(Modifier.width(24.dp).height(1.dp).background(if (chosen) color else androidx.compose.ui.graphics.Color.Transparent))
                                        }
                                    }
                                }
                            }
                        }
                        if (active == SharedCategory.All) {
                            listOf(SharedCategory.Media, SharedCategory.Circles, SharedCategory.Music, SharedCategory.Files, SharedCategory.Links, SharedCategory.Voice).forEach { section ->
                                val preview = state.pages[section] ?: ContactCategoryState()
                                if (preview.entries.isNotEmpty()) {
                                    item(key = "title:${section.name}", contentType = "section") {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text(sectionTitle(section), style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
                                            if (preview.more || preview.entries.size > 4) DefaultAction("View All →", { category(section) }, compact = true)
                                        }
                                    }
                                    sharedEntries(preview.entries.take(4), section, 2, detail?.protectedContent ?: true, media) { openEntry(it, section) }
                                }
                                if (preview.error != null) item(key = "error:${section.name}", contentType = "error") {
                                    Text("${section.label}: ${preview.error}", style = MaterialTheme.typography.labelSmall, color = DefaultDesign.colors.secondary)
                                    DefaultAction("Retry", { vm.load(section, preview = true) }, compact = true)
                                }
                            }
                            if (state.pages.values.any { it.loading }) item(key = "overview_loading") { Text("Loading shared content…", color = DefaultDesign.colors.secondary) }
                            else if (state.pages.isNotEmpty() && state.pages.values.all { it.loaded && it.entries.isEmpty() }) item(key = "empty") {
                                Text("No shared content", color = DefaultDesign.colors.secondary)
                            }
                        } else {
                            sharedEntries(page.entries, active, if (active == SharedCategory.Circles) 2 else 3, detail?.protectedContent ?: true, media) { openEntry(it, active) }
                            item(key = "pagination", contentType = "pagination") {
                                if (page.loading) Text("Loading…", color = DefaultDesign.colors.secondary)
                                else if (page.error != null) { Text(page.error, color = DefaultDesign.colors.secondary); DefaultAction("Retry", { vm.load(active) }, compact = true) }
                                else if (!page.loaded || page.more) DefaultAction("Load more", { vm.load(active) }, compact = true)
                                else if (page.entries.isEmpty()) Text("No shared ${active.label.lowercase()}", color = DefaultDesign.colors.secondary)
                            }
                        }
                    }
                }
            }
        if (menu) DefaultDialog("Contact", { menu = false }) {
            if (detail != null) {
                if (detail.canMute) DefaultAction(if (detail.muted) "Unmute" else "Mute", { menu = false; vm.mute() }, enabled = !state.busy, compact = true)
                if (detail.user != null) DefaultAction("Telegram call history", { menu = false; callsOpen = true }, compact = true)
                DefaultAction("Contact details", { menu = false; detailsOpen = true }, compact = true)
                detail.username?.let { username -> DefaultAction("Share profile link", {
                    menu = false
                    runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, "https://t.me/$username"), "Share Telegram profile")) }
                }, compact = true) }
            }
            DefaultAction("Refresh", { menu = false; vm.refresh() }, compact = true)
        }
        if (callsOpen) com.vector.messenger.calling.CallHistoryDialog(chat) { callsOpen = false }
        if (detailsOpen && detail != null) DefaultDialog("Contact details", { detailsOpen = false }) {
            Text(detail.name, color = DefaultDesign.colors.text)
            detail.phone?.let { Text(if (it.startsWith('+')) it else "+$it", color = DefaultDesign.colors.secondary) }
            detail.username?.let { Text("@$it", color = DefaultDesign.colors.secondary) }
            detail.user?.let { Text("Telegram ID  $it", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall) }
        }
        selected?.let { id ->
            val source = state.pages[SharedCategory.valueOf(viewerCategory)] ?: ContactCategoryState()
            val entries = source.entries.mapNotNull { it.media(detail?.protectedContent ?: true) }
            if (entries.any { it.messageId == id }) {
                if (SharedCategory.valueOf(viewerCategory) == SharedCategory.Circles) FloatingVideoNoteViewer(
                    source.entries.sortedWith(compareBy<SharedEntry> { it.message.date }.thenBy { it.message.id }).mapNotNull { it.media(detail?.protectedContent ?: true) }, id, media, source.more, source.loading, source.error,
                    { vm.load(SharedCategory.Circles) }, { selected = null })
                else MediaViewer(entries, id, media) { selected = null }
            } else LaunchedEffect(id) { selected = null }
        }
        }
    }
}

private fun sectionTitle(category: SharedCategory) = when (category) {
    SharedCategory.Media -> "RECENTLY SHARED"; SharedCategory.Circles -> "VIDEO CIRCLES"; SharedCategory.Music -> "SHARED MUSIC"
    SharedCategory.Files -> "SHARED FILES"; SharedCategory.Links -> "SHARED LINKS"; SharedCategory.Voice -> "VOICE MESSAGES"; else -> category.label.uppercase()
}

private fun LazyListScope.sharedEntries(entries: List<SharedEntry>, category: SharedCategory, columns: Int,
    protectedContent: Boolean, media: MediaViewModel, open: (SharedEntry) -> Unit) {
    if (category in listOf(SharedCategory.Media, SharedCategory.Circles, SharedCategory.GIFs)) {
        items(entries.chunked(columns), key = { row -> "row:${category.name}:${row.first().message.id}" }, contentType = { "shared_grid" }) { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { entry -> key(entry.message.id) {
                    Box(Modifier.weight(1f)) { SharedThumbnail(entry, protectedContent, media, category == SharedCategory.Circles) { open(entry) } }
                } }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    } else items(entries, key = { "${category.name}:${it.message.id}" }, contentType = { category.name }) { entry ->
        when (category) {
            SharedCategory.Links -> SharedLinks(entry, protectedContent, media)
            SharedCategory.Music, SharedCategory.Voice -> entry.media(protectedContent)?.let { item ->
                Column {
                    if (category == SharedCategory.Music) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (item.media.thumbnail != null) Box(Modifier.size(36.dp)) {
                                SharedThumbnail(entry, protectedContent, media, false) { open(entry) }
                            } else DesignIcon(VectorIcons.Music, null, Modifier.size(20.dp), tint = DefaultDesign.colors.accent)
                            Text(item.media.name, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        item.media.artist.takeIf(String::isNotBlank)?.let { Text(it, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall) }
                    }
                    if (category == SharedCategory.Voice) entry.message.senderName?.let {
                        Text(it, color = DefaultDesign.colors.text, style = MaterialTheme.typography.labelSmall)
                    }
                    VoiceAttachment(item, media)
                    if (category == SharedCategory.Voice) Text(messageDate(entry.message.date), color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                }
            }
            SharedCategory.Files -> entry.media(protectedContent)?.let { SharedFile(it, media) { open(entry) } }
            else -> Unit
        }
    }
}

@Composable
private fun SharedThumbnail(entry: SharedEntry, protectedContent: Boolean, media: MediaViewModel, circle: Boolean, onOpen: () -> Unit) {
    val item = entry.media(protectedContent) ?: return
    val presentation by remember(item.media.file.id, item.media.thumbnail?.id) { media.observe(item) }.collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val thumbnail = presentation.thumbnail?.path
    val full = presentation.file?.takeIf { it.complete }?.path
    val context = LocalContext.current
    val image by produceState<DecodedMediaImage?>(null, thumbnail, full, presentation.session) {
        value = thumbnail?.let { MediaImages.load(it, 360, presentation.session) }
            ?: full?.let { if (item.media.kind == MediaKind.Photo) MediaImages.load(it, 360, presentation.session)
                else if (item.media.kind in listOf(MediaKind.Video, MediaKind.VideoNote)) MediaImages.loadVideoUri(context, Uri.fromFile(java.io.File(it)).toString(), presentation.session, 360) else null }
    }
    LaunchedEffect(item.media.file.id, item.media.thumbnail?.id, presentation.session) {
        item.media.thumbnail?.let { media.preview(item.copy(media = item.media.copy(file = it, thumbnail = null))) }
        if (item.media.file.complete) media.preview(item)
    }
    Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(if (circle) CircleShape else RoundedCornerShape(10.dp))
        .clickable(onClickLabel = "Open ${item.media.kind.name.lowercase()}", onClick = onOpen), contentAlignment = Alignment.Center) {
        if (image != null) Image(image!!.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else DesignIcon(if (circle) VectorIcons.Clock else VectorIcons.Download, "Thumbnail unavailable", tint = DefaultDesign.colors.secondary)
        if (item.media.kind in listOf(MediaKind.Video, MediaKind.VideoNote)) {
            VideoNotePlayGlyph()
            VideoNoteDuration(item.media.duration, Modifier.align(Alignment.BottomStart).padding(5.dp))
        } else if (item.media.kind == MediaKind.Animation) Text("GIF", Modifier.align(Alignment.BottomStart).padding(6.dp), color = DefaultDesign.colors.text, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun SharedFile(item: MediaItem, media: MediaViewModel, onOpen: () -> Unit) {
    val presentation by remember(item.media.file.id) { media.observe(item) }.collectAsStateWithLifecycle(initialValue = MediaPresentation())
    val file = presentation.file
    Column(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 4.dp)) {
        Text(item.media.name, maxLines = 2, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
        Text("${item.media.mime} · ${fileSize(file?.size ?: item.media.file.size)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DefaultAction(if (file?.active == true) "Pause ${(file.progress * 100).toInt()}%" else if (file?.complete == true) "View" else "Download / retry",
                { if (file?.active == true) media.pause(item.media.file.id) else if (file?.complete == true) onOpen() else media.download(item) }, compact = true)
            if (file?.complete == true && item.exportAllowed) {
                val context = LocalContext.current
                DefaultAction("Open file", { media.external(context, item, false) }, compact = true)
            }
        }
    }
}

@Composable
private fun SharedLinks(entry: SharedEntry, protectedContent: Boolean, media: MediaViewModel) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    entry.links.forEach { link ->
        Column(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            link.image?.let { image -> Box(Modifier.size(54.dp)) {
                SharedThumbnail(SharedEntry(entry.message.copy(media = image)), protectedContent, media, false) {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) }
                }
            } }
            Text(Uri.parse(link.url).host.orEmpty(), color = DefaultDesign.colors.accent, style = MaterialTheme.typography.labelSmall)
            link.title?.let { Text(it, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium) }
            Text(link.url, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DefaultAction("Open", { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link.url))) } }, compact = true)
                DefaultAction("Copy", { clipboard.setText(AnnotatedString(link.url)) }, compact = true)
            }
        }
    }
}
private fun messageDate(seconds: Long): String = if (seconds <= 0) "" else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(seconds))
