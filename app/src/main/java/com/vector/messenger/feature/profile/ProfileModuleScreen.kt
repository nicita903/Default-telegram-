package com.vector.messenger.feature.profile

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vector.messenger.telegram.TelegramPost
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vector.messenger.design.*
import com.vector.messenger.feature.avatar.*
import com.vector.messenger.feature.media.*
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.telegram.AvatarOwner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

internal val LocalProfileModuleViewModel = staticCompositionLocalOf<ProfileModuleViewModel?> { null }

@Composable
internal fun ProfileModuleHome(vm: ProfileModuleViewModel, onEdit: () -> Unit, onNew: () -> Unit, onPost: (String) -> Unit) {
    val identities by vm.identities.collectAsStateWithLifecycle()
    val selected by vm.displayedProfile.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    val login = LocalAccountAction.current
    if (identities.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (account.loading) "Loading…" else "Sign in to view your profile.", color = DefaultDesign.colors.secondary)
            if (!account.loading) DefaultAction("Telegram account", login)
        }
        return
    }
    ProfileIdentityPager(identities, selected, vm::displayProfile) { identity ->
        LaunchedEffect(identity.key) { vm.ensureProfile(identity.key) }
        if (identity.key.providerId == TelegramProfileIdentityAdapter.Provider) {
            TelegramProfilePage(vm, identity, onEdit, onNew, onPost)
        } else {
            // Future connected providers supply only their real identity/capabilities. No fake
            // Posts section or Telegram editing is attached to another provider's account.
            Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) { ProfileIdentityContent(identity) }
        }
    }
}

@Composable
private fun ProfileIdentityContent(identity: ProfileIdentity) {
    when (val avatar = identity.avatar) {
        is ProfileAvatar.Telegram -> TelegramAvatar(identity.displayName, avatar.photo, 96.dp, avatar.userId, user = true)
        is ProfileAvatar.LocalFile -> {
            val decoded by produceState<DecodedMediaImage?>(null, identity.key, avatar.path) {
                value = MediaImages.load(avatar.path, 320, identity.key.saveKey.hashCode().toLong())
            }
            if (decoded == null) ChatAvatar(identity.displayName, 96.dp, identity.key.saveKey.hashCode().toLong())
            else Image(decoded!!.bitmap.asImageBitmap(), "${identity.displayName} profile picture", Modifier.size(96.dp).clip(DefaultDesign.avatarShape), contentScale = ContentScale.Crop)
        }
        null -> ChatAvatar(identity.displayName, 96.dp, identity.key.saveKey.hashCode().toLong())
    }
    Text(identity.displayName, color = DefaultDesign.colors.text, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
    identity.username?.takeIf { it.isNotBlank() }?.let { Text("@$it", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodyMedium) }
    identity.biography?.takeIf { it.isNotBlank() }?.let { Text(it, color = DefaultDesign.colors.secondary, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium) }
}

@Composable
private fun TelegramProfilePage(vm: ProfileModuleViewModel, identity: ProfileIdentity, onEdit: () -> Unit, onNew: () -> Unit, onPost: (String) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val storyState by vm.storyState.collectAsStateWithLifecycle()
    val colors = DefaultDesign.colors
    val posts = if (storyState.account.toString() == identity.key.accountId) storyState.posts else emptyList()
    val grid = rememberLazyGridState()
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize(), state = grid, contentPadding = PaddingValues(20.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(key = "identity", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileIdentityContent(identity)
                if (identity.capabilities.canEdit) Text("EDIT PROFILE", Modifier.heightIn(min = 40.dp).excludeModuleGestures()
                    .clickable(enabled = state.details != null && !state.loading, role = Role.Button) { vm.beginEdit(); onEdit() }
                    .padding(horizontal = 12.dp, vertical = 10.dp), color = colors.accent, style = MaterialTheme.typography.labelLarge)
                if (state.loading && state.details == null) Text("Loading…", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                state.error?.let { Text(it, color = colors.secondary, style = MaterialTheme.typography.bodySmall) }
                if (state.error != null) DefaultAction("Retry", vm::reload, compact = true)
            }
        }
        if (identity.capabilities.canReadPosts) {
            item(key = "posts_heading", span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    DefaultRule()
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("POSTS", Modifier.weight(1f), color = colors.text, style = MaterialTheme.typography.titleMedium)
                        if (storyState.loading) Text("Loading…", color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                        else DefaultIconControl(VectorIcons.Refresh, "Refresh posts", { vm.stories.refresh() }, quiet = true)
                        if (identity.capabilities.canPublish) DefaultAction("+ Add", onNew, compact = true)
                    }
                    storyState.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (posts.isEmpty() && !storyState.loading && storyState.error == null) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                Text("No posts yet.", Modifier.fillMaxWidth().padding(vertical = 24.dp), textAlign = TextAlign.Center, color = colors.secondary)
            }
            items(posts, key = { "${identity.key.saveKey}:${it.id}" }, contentType = { "telegram_story" }) { post ->
                val preview = post.thumbnail ?: post.file?.takeUnless { post.video }
                LaunchedEffect(identity.key, preview?.fileId) { preview?.let { vm.stories.download(it.fileId) } }
                Column(Modifier.clickable(onClickLabel = "Open Telegram profile Story") { onPost(post.id.toString()) }) {
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(16.dp))) {
                        PostImage(preview?.let { storyState.files[it.fileId] ?: it }?.localPath, storyState.account, Modifier.fillMaxSize(), if (post.video) "Video" else "Photo")
                        if (post.video) Text("▶", Modifier.align(Alignment.TopEnd).padding(8.dp), color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp)
                    }
                    StoryInteractionCounts(post, Modifier.padding(top = 4.dp))
                    if (post.caption.isNotBlank()) Text(post.caption, Modifier.padding(top = 2.dp), color = colors.secondary,
                        style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (storyState.more && storyState.error == null && !storyState.loading) item(key = "story_more", span = { GridItemSpan(maxLineSpan) }) {
                DefaultAction("Load more", { vm.stories.refresh(more = true) }, compact = true)
            }
        }
    }
}

/** Both values come from TDLib interaction_info. Heart denotes total reactions, not heart likes. */
@Composable
private fun StoryInteractionCounts(post: TelegramPost, modifier: Modifier = Modifier) {
    if (post.viewCount == null && post.reactionCount == null) return
    val colors = DefaultDesign.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        post.viewCount?.let { count -> Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$count views" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DesignIcon(VectorIcons.Views, null, Modifier.size(12.dp), tint = colors.secondary)
            Text(count.toString(), color = colors.secondary, fontSize = 11.sp, lineHeight = 14.sp)
        } }
        post.reactionCount?.let { count -> Row(Modifier.semantics(mergeDescendants = true) { contentDescription = "$count reactions" },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            DesignIcon(VectorIcons.Heart, null, Modifier.size(12.dp), tint = colors.secondary)
            Text(count.toString(), color = colors.secondary, fontSize = 11.sp, lineHeight = 14.sp)
        } }
    }
}

@Composable
internal fun EditProfileScreen(vm: ProfileModuleViewModel, onDone: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val first by vm.first.collectAsStateWithLifecycle(); val last by vm.last.collectAsStateWithLifecycle()
    val username by vm.username.collectAsStateWithLifecycle(); val bio by vm.bio.collectAsStateWithLifecycle()
    val avatars = LocalAvatarViewModel.current
    val own = account.profile?.takeIf { account.authenticated }
    var savedVersion by rememberSaveable(own?.id) { mutableIntStateOf(state.saved) }
    LaunchedEffect(state.saved) { if (state.saved != savedVersion) { savedVersion = state.saved; onDone() } }
    LaunchedEffect(state.details?.account) { vm.beginEdit() }
    if (own == null) { Text("Sign in to edit your Telegram profile.", Modifier.padding(24.dp), color = DefaultDesign.colors.secondary); return }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TelegramAvatar(own.displayName, own.photo, 96.dp, own.id, user = true)
        DefaultAction("Change profile photo", { avatars?.changeOwnPhoto() }, compact = true, enabled = !state.saving)
        if (own.photo != null) DefaultAction("View / remove profile photo", { avatars?.open(AvatarOwner(own.id), own.displayName, own.photo) }, compact = true, enabled = !state.saving)
        ProfileEditorField("FIRST NAME", first, { vm.field("first", it) }, !state.saving)
        ProfileEditorField("LAST NAME", last, { vm.field("last", it) }, !state.saving)
        ProfileEditorField("USERNAME", username, { vm.field("username", it) }, !state.saving)
        ProfileEditorField("BIO", bio, { vm.field("bio", it) }, !state.saving, multiline = true)
        state.details?.let { Text("${bio.codePointCount(0, bio.length)} / ${it.bioLimit}", Modifier.align(Alignment.End), color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        DefaultAction(if (state.saving) "Saving to Telegram…" else "Save", vm::saveProfile, enabled = state.details != null && !state.saving && !state.loading)
        if (state.error != null) DefaultAction("Refresh Telegram values", vm::reload, enabled = !state.saving, compact = true)
    }
}

@Composable
private fun ProfileEditorField(label: String, value: String, onChange: (String) -> Unit, enabled: Boolean, multiline: Boolean = false) {
    Column(Modifier.fillMaxWidth().excludeModuleGestures(), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        DefaultInput(value, { if (enabled) onChange(it) }, if (label == "BIO") "Write something about yourself…" else label.lowercase(),
            Modifier.fillMaxWidth(), singleLine = !multiline, maxLines = if (multiline) 5 else 1, framed = false)
        DefaultRule()
    }
}

@Composable
internal fun NewPostScreen(vm: ProfileModuleViewModel, onDone: () -> Unit) {
    val account by vm.account.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val story by vm.storyState.collectAsStateWithLifecycle()
    val text by vm.text.collectAsStateWithLifecycle(); val media by vm.media.collectAsStateWithLifecycle()
    val visibility by vm.visibility.collectAsStateWithLifecycle(); val selected by vm.selectedUsers.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle(); val query by vm.contactQuery.collectAsStateWithLifecycle()
    val contactError by vm.contactError.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.selectMedia(it.map(Uri::toString)) }
    val context = LocalContext.current
    var postedVersion by rememberSaveable(account.profile?.id) { mutableIntStateOf(state.posted) }
    var releaseUnknown by remember { mutableStateOf(false) }
    LaunchedEffect(state.posted) { if (postedVersion != state.posted) { postedVersion = state.posted; onDone() } }
    if (!account.authenticated || account.profile == null) { Text("Sign in to publish Telegram Stories.", Modifier.padding(24.dp), color = DefaultDesign.colors.secondary); return }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Telegram Story · Active for 24 hours · Remains on your profile", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        media.forEach { uri -> key(uri) {
            val type = remember(uri) { context.contentResolver.getType(Uri.parse(uri)).orEmpty() }
            Column {
                val image by produceState<DecodedMediaImage?>(null, uri) {
                    value = if (type.startsWith("video/")) MediaImages.loadVideoUri(context, uri, account.profile!!.id)
                        else MediaImages.loadUri(context, uri, account.profile!!.id, 800)
                }
                Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(androidx.compose.ui.graphics.Color.Black), contentAlignment = Alignment.Center) {
                    image?.let { Image(it.bitmap.asImageBitmap(), "Selected Story media preview", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    if (image == null) Text("Preview unavailable", color = DefaultDesign.colors.secondary)
                }
                DefaultAction("Remove selected media", { vm.removeMedia(uri) }, enabled = !state.posting, compact = true)
            }
        } }
        DefaultInput(text, vm::postText, "Add caption…", Modifier.fillMaxWidth().heightIn(min = 90.dp).excludeModuleGestures(), singleLine = false, maxLines = 8, framed = false)
        DefaultAction("+ Add photo / video", { picker.launch(arrayOf("image/*", "video/mp4")) }, enabled = media.isEmpty() && !state.posting)
        Text("Photos are fitted to 1080×1920 without cropping. Video must already be streamable 720×1280 H.265 MP4, up to 60 seconds; video conversion is not available.", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        Text("VISIBILITY", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Everyone", "Contacts", "Selected").forEach { mode ->
                DefaultAction(if (visibility == mode) "● $mode" else mode, { vm.visibility(mode) }, compact = true, enabled = !state.posting)
            }
        }
        if (visibility.isBlank()) Text("Choose who can view this Story before publishing.", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        if (visibility == "Selected") {
            DefaultInput(query, vm::searchContacts, "Search Telegram contacts", Modifier.fillMaxWidth().excludeModuleGestures())
            Text("${selected.size} selected", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
            contactError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(contacts, key = { it.owner.id }, contentType = { "profile_contact" }) { contact ->
                    Row(Modifier.fillMaxWidth().excludeModuleGestures().clickable(enabled = !state.posting) { vm.toggleUser(contact.owner.id) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        TelegramAvatar(contact.name, contact.small, 36.dp, contact.owner.id, user = true)
                        Text(contact.name, Modifier.weight(1f).padding(start = 10.dp), color = DefaultDesign.colors.text)
                        Text(if (contact.owner.id in selected) "●" else "○", color = DefaultDesign.colors.accent)
                    }
                }
            }
        }
        (story.publishError ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        if (story.posting) Text(story.progress?.let { "Uploading to Telegram · ${(it * 100).toInt()}% · waiting for publication confirmation" }
            ?: "Preparing or posting to Telegram… Waiting for confirmation.", color = DefaultDesign.colors.secondary)
        if (story.posting || story.uncertain) DefaultAction("Check publication", vm.stories::checkPending, compact = true)
        if (story.uncertain && !story.posting) DefaultAction("I checked Telegram — allow another post", { releaseUnknown = true }, compact = true)
        if (releaseUnknown) DefaultDialog("Allow another Story?", { releaseUnknown = false }) {
            Text("The previous Story may already be published or still uploading. Continue only after checking Telegram to avoid duplicates.", color = DefaultDesign.colors.secondary)
            DefaultAction("Allow another post", { releaseUnknown = false; vm.stories.acknowledgeUnknown() })
            DefaultAction("Cancel", { releaseUnknown = false })
        }
    }
}

@Composable
internal fun TelegramPostScreen(vm: ProfileModuleViewModel, id: Int, onBack: () -> Unit) {
    val story by vm.storyState.collectAsStateWithLifecycle()
    val account by vm.account.collectAsStateWithLifecycle()
    var currentId by rememberSaveable(story.account, id) { mutableIntStateOf(id) }
    val available = if (story.account == account.profile?.id && account.authenticated) story.posts else emptyList()
    val post = available.find { it.id == currentId }
    fun navigate(delta: Int) {
        val index = available.indexOfFirst { it.id == currentId }
        available.getOrNull(index + delta)?.takeIf { index >= 0 }?.let { currentId = it.id }
    }
    var confirmDelete by remember { mutableStateOf<Boolean?>(null) }
    var footerHeight by remember(id) { mutableIntStateOf(0) }
    val footerPadding = with(LocalDensity.current) { footerHeight.toDp() }
    var controls by rememberSaveable(id) { mutableStateOf(true) }
    LifecycleStartEffect(currentId, story.chat) {
        val openedId = currentId
        vm.stories.open(openedId)
        onStopOrDispose { vm.stories.close(openedId) }
    }
    LaunchedEffect(post?.file?.fileId) { post?.file?.let { vm.stories.download(it.fileId) }; post?.thumbnail?.let { vm.stories.download(it.fileId) } }
    if (post == null) {
        Column(Modifier.padding(24.dp)) {
            Text(story.error ?: "This Telegram profile Story is unavailable.", color = DefaultDesign.colors.secondary)
            DefaultAction("Refresh posts", { vm.stories.refresh() }); DefaultAction("Back", onBack)
        }
        return
    }
    val context = LocalContext.current
    val window = remember(context) {
        generateSequence(context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
    }
    DisposableEffect(window, post.protected) {
        val flag = android.view.WindowManager.LayoutParams.FLAG_SECURE
        val added = post.protected && window != null && window.attributes.flags and flag == 0
        if (added) window?.addFlags(flag)
        onDispose { if (added) window?.clearFlags(flag) }
    }
    val file = post.file?.let { story.files[it.fileId] ?: it }
    val thumb = post.thumbnail?.let { story.files[it.fileId] ?: it }
    Box(Modifier.fillMaxSize().excludeModuleGestures()) {
        key(post.id, post.chat) {
            val mediaPath = file?.localPath
            if (post.video && file != null && mediaPath != null) VideoMedia(mediaPath, file.fileId, false, PaddingValues(bottom = footerPadding),
                Modifier.storyVideoGestures(post.id, ::navigate, onBack))
            else {
                val path = file?.localPath ?: thumb?.localPath
                val decoded by produceState<DecodedMediaImage?>(null, path, post.chat) { value = path?.let { MediaImages.load(it, 2048, post.chat) } }
                ZoomableImage(decoded, post.width, post.height, if (!post.video) file?.localPath else null, post.id,
                    "Telegram Story", if (post.video) "Downloading video…" else "Downloading photo…", { controls = !controls }, onBack, ::navigate)
            }
        }
        if (controls || post.video) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 280.dp)
            .onSizeChanged { footerHeight = it.height }.background(DefaultDesign.colors.base.copy(alpha = .84f)).verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val date = remember(post.date) { DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(post.date)) }
            Text(date, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
            StoryInteractionCounts(post)
            if (post.caption.isNotBlank()) androidx.compose.foundation.text.selection.SelectionContainer {
                Text(post.caption, color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyLarge)
            }
            story.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (file?.localPath == null) DefaultAction("Download / retry", { post.file?.let { vm.stories.download(it.fileId, force = true) } }, compact = true)
            if (available.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DefaultAction("Previous", { navigate(-1) }, compact = true, enabled = available.indexOf(post) > 0)
                DefaultAction("Next", { navigate(1) }, compact = true, enabled = available.indexOf(post) < available.lastIndex)
            }
            if (post.canRemove) DefaultAction("Remove from profile", { confirmDelete = false }, compact = true, enabled = !story.posting)
            if (post.canDelete) DefaultAction("Delete Story", { confirmDelete = true }, destructive = true, compact = true, enabled = !story.posting)
        }
        confirmDelete?.let { delete -> DefaultDialog(if (delete) "Delete Telegram Story?" else "Remove from profile?", { confirmDelete = null }) {
            Text(if (delete) "Permanently delete this Story from Telegram?" else "Make this Story private after expiration by removing it from your Telegram profile?", color = DefaultDesign.colors.secondary)
            DefaultAction(if (delete) "Delete" else "Remove", { confirmDelete = null; vm.delete(post, delete, onBack) }, destructive = delete, enabled = !story.posting)
            DefaultAction("Cancel", { confirmDelete = null })
        } }
    }
}

@Composable
private fun PostImage(path: String?, account: Long, modifier: Modifier, fallback: String) {
    val context = LocalContext.current
    val decoded by produceState<DecodedMediaImage?>(null, path, account) {
        value = path?.let { MediaImages.load(it, 320, account) ?: if (fallback == "Video")
            MediaImages.loadVideoUri(context, Uri.fromFile(java.io.File(it)).toString(), account, 320) else null }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val image = decoded
        if (image != null) Image(image.bitmap.asImageBitmap(), fallback, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Text(fallback, color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
    }
}
