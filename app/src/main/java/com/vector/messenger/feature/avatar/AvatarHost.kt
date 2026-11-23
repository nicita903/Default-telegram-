package com.vector.messenger.feature.avatar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.PhotoQuality
import com.vector.messenger.design.*
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.media.*
import com.vector.messenger.telegram.AvatarPicture
import kotlinx.coroutines.launch

@Composable
fun AvatarHost(avatars: AvatarViewModel, chats: ChatsViewModel) {
    val viewing by avatars.viewer.collectAsStateWithLifecycle()
    val cache by avatars.state.collectAsStateWithLifecycle()
    val edit by avatars.edit.collectAsStateWithLifecycle()
    val selectPhoto by avatars.photoSelection.collectAsStateWithLifecycle()
    val current = viewing ?: return
    var gallery by rememberSaveable(cache.session, current.identity.owner) { mutableStateOf(false) }
    var selection by rememberSaveable(cache.session, current.identity.owner) { mutableStateOf(arrayListOf<String>()) }
    var crop by rememberSaveable(cache.session, current.identity.owner) { mutableStateOf<String?>(null) }
    var completed by rememberSaveable(cache.session, current.identity.owner) { mutableIntStateOf(edit.completed) }
    LaunchedEffect(edit.completed) { if (completed != edit.completed) { completed = edit.completed; gallery = false; crop = null } }
    LaunchedEffect(selectPhoto) { if (selectPhoto && avatars.isOwn(current.identity)) { gallery = true; avatars.consumePhotoSelection() } }
    key(cache.session, current.identity.owner) { AvatarViewerDialog(avatars, current, cache, edit) { gallery = true } }
    if (gallery) PhotoGalleryPanel(chats, selection, PhotoQuality.HD, edit.busy, false, edit.error,
        onSelection = { selection = ArrayList(it) }, onPreview = { selection = arrayListOf(it); crop = it },
        onSend = { selection.firstOrNull()?.let { crop = it } }, onDismiss = { gallery = false }, singleSelection = true)
    crop?.let { uri -> AvatarCropDialog(uri, chats, avatars, edit) { crop = null } }
}

@Composable
private fun AvatarViewerDialog(avatars: AvatarViewModel, state: AvatarViewerState, cache: AvatarCache, edit: AvatarEditState, onChange: () -> Unit) {
    val colors = DefaultDesign.colors
    val appearance = LocalAppearance.current
    val account = LocalAccountAction.current
    val scope = rememberCoroutineScope()
    var index by rememberSaveable(cache.session, state.identity.owner, state.revision) { mutableIntStateOf(0) }
    val photo = state.pictures.getOrNull(index)
    var controls by rememberSaveable(cache.session, state.identity.owner) { mutableStateOf(true) }
    var info by remember { mutableStateOf(false) }
    var memberList by remember { mutableStateOf(false) }
    val members by avatars.members.collectAsStateWithLifecycle()
    var remove by remember { mutableStateOf<AvatarPicture?>(null) }
    var pendingSave by remember { mutableStateOf<AvatarPicture?>(null) }
    var saveNotice by remember { mutableStateOf<String?>(null) }
    val session = cache.session
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("image/jpeg")) { uri: Uri? ->
        val requested = pendingSave
        pendingSave = null
        if (uri != null && requested != null) scope.launch {
            saveNotice = if (avatars.savePhoto(requested, uri.toString(), session, state.identity.owner)) "Photo saved" else "Unable to save this photo or saving is restricted."
        }
    }
    LaunchedEffect(state.pictures.size) { index = index.coerceIn(0, (state.pictures.size - 1).coerceAtLeast(0)) }
    LaunchedEffect(photo?.big?.fileId, session) {
        photo?.big?.let { avatars.download(it.fileId) }
        photo?.small?.let { avatars.download(it.fileId) }
        state.pictures.getOrNull(index + 1)?.small?.let { avatars.download(it.fileId) }
        if (index >= state.pictures.lastIndex - 2) avatars.moreHistory()
    }
    Dialog(onDismissRequest = avatars::close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        FloatingBackdropHost {
        Box(Modifier.fillMaxSize().designCanvas(colors.mediaCanvas)) {
            if (photo != null) key(session, photo.big.fileId) {
                val full = cache.files[photo.big.fileId] ?: photo.big
                val thumbnail = photo.small?.let { cache.files[it.fileId] ?: it }
                val path = full.localPath ?: thumbnail?.localPath
                val decoded by produceState<DecodedMediaImage?>(null, path, session) {
                    value = path?.let { MediaImages.load(it, 2048, session) }
                    if (path != null && value == null) avatars.missingFile(if (full.localPath != null) full.fileId else thumbnail!!.fileId)
                    else if (value != null) avatars.decoded(if (full.localPath != null) full.fileId else thumbnail!!.fileId)
                }
                ZoomableImage(decoded, decoded?.width ?: 0, decoded?.height ?: 0, full.localPath, photo.big.fileId,
                    "${state.identity.name} profile photo", "Loading photo…", onTap = { controls = !controls }, onClose = avatars::close,
                    onNavigate = { delta -> if (index + delta in state.pictures.indices) index += delta else if (delta > 0) avatars.moreHistory() })
            } else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { ChatAvatar(state.identity.name, 120.dp, state.identity.userId ?: state.identity.owner.id) }
            AnimatedVisibility(controls, modifier = Modifier.align(Alignment.TopCenter), enter = VectorMotionSpec.lockEnter(appearance), exit = VectorMotionSpec.lockExit(appearance)) {
                Row(Modifier.fillMaxWidth().safeDrawingPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DefaultIconControl(Icons.Default.Close, "Close profile photo", avatars::close, enabled = !edit.busy, quiet = true)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(state.identity.name, color = colors.text, style = MaterialTheme.typography.titleMedium)
                        Text(if (state.pictures.isEmpty()) "No available profile photo" else "${index + 1} / ${state.pictures.size}${if (state.more) "+" else ""}", color = colors.secondary)
                    }
                }
            }
            AnimatedVisibility(controls, modifier = Modifier.align(Alignment.BottomCenter), enter = VectorMotionSpec.lockEnter(appearance), exit = VectorMotionSpec.lockExit(appearance)) {
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (edit.error ?: state.error ?: saveNotice)?.let { Text(it, color = colors.secondary, style = MaterialTheme.typography.bodySmall) }
                    if (state.loading || edit.busy) Text(if (edit.busy) "Updating profile photo…" else "Loading available photos…", color = colors.secondary)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        DefaultAction("View profile", { if (avatars.isOwn(state.identity)) { avatars.close(); account() } else info = true }, compact = true, enabled = !edit.busy)
                        if (avatars.isOwn(state.identity)) {
                            DefaultAction("Change photo", onChange, compact = true, enabled = !edit.busy)
                            if (photo != null && photo.id != 0L) DefaultAction("Remove photo", { remove = photo }, compact = true, enabled = !edit.busy, destructive = true)
                        }
                        if (photo != null && state.identity.canSave) DefaultAction("Save photo", {
                            pendingSave = photo; save.launch("Profile photo.jpg")
                        }, compact = true, enabled = (cache.files[photo.big.fileId]?.localPath ?: photo.big.localPath) != null && !edit.busy)
                        DefaultAction("Refresh", avatars::refresh, compact = true, enabled = !edit.busy)
                        if (state.more) DefaultAction("More photos", avatars::moreHistory, compact = true, enabled = !state.loading)
                    }
                    if (state.pictures.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DefaultAction("Previous", { index-- }, enabled = index > 0, compact = true)
                        DefaultAction("Next", { index++ }, enabled = index < state.pictures.lastIndex, compact = true)
                    }
                }
            }
            if (info) DefaultDialog("Profile", { info = false }) {
                Text(state.identity.name, color = colors.text)
                state.identity.username?.let { Text("@$it", color = colors.secondary) }
                Text("${if (state.identity.userId != null) "User" else "Chat"} ID: ${state.identity.userId ?: state.identity.owner.id}", color = colors.secondary)
                if (state.identity.userId == null) DefaultAction("Members", { info = false; memberList = true; avatars.loadMembers() })
                DefaultAction("Done", { info = false })
            }
            if (memberList) DefaultDialog("Members", { memberList = false }) {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(members.entries, key = { it.owner.id }) { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            TelegramAvatar(member.name, member.small, 40.dp, member.owner.id, user = true)
                            Text(member.name, Modifier.padding(start = 10.dp), color = colors.text, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                members.notice?.let { Text(it, color = colors.secondary, style = MaterialTheme.typography.bodySmall) }
                if (members.loading) Text("Loading members…", color = colors.secondary)
                else if (members.more) DefaultAction("Load members", avatars::loadMembers, compact = true)
                DefaultAction("Done", { memberList = false })
            }
            remove?.let { selected -> DefaultDialog("Remove profile photo?", { remove = null }) {
                Text("This removes the selected photo from your Telegram profile.", color = colors.secondary)
                DefaultAction("Remove", { remove = null; avatars.remove(selected) }, destructive = true)
                DefaultAction("Cancel", { remove = null })
            } }
        }
        }
    }
}
