package com.vector.messenger.feature.media

import android.content.Intent
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.*
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.PhotoQuality
import com.vector.messenger.design.*
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.LocalAppearance
import kotlinx.coroutines.flow.distinctUntilChanged

/** Native MediaStore only. The permission dialog may limit access; the gallery never opens a provider picker. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PhotoGalleryPanel(
    viewModel: ChatsViewModel, selected: List<String>, quality: PhotoQuality, busy: Boolean, uncertain: Boolean, error: String?,
    onSelection: (List<String>) -> Unit, onPreview: (String) -> Unit, onSend: () -> Unit, onDismiss: () -> Unit,
    singleSelection: Boolean = false,
) {
    val library = viewModel.photoGallery ?: return
    val scope = rememberCoroutineScope()
    val controller = remember(library, scope) { LocalGalleryController(library, scope) }
    val state by controller.state.collectAsStateWithLifecycle()
    val appearance = LocalAppearance.current
    val colors = DefaultDesign.colors
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val grid = rememberLazyGridState()
    var foldersOpen by remember { mutableStateOf(false) }
    var closing by remember { mutableStateOf(false) }
    var limit by remember { mutableStateOf(false) }
    val transition = remember { MutableTransitionState(false).apply { targetState = true } }
    val latestSelection by rememberUpdatedState(selected)
    val updateSelection by rememberUpdatedState(onSelection)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { controller.refresh() }
    fun dismiss() { if (!busy) { closing = true; transition.targetState = false } }
    fun toggle(uri: String) {
        if (busy || closing) return
        limit = false
        if (uri in selected) onSelection(selected.filterNot { it == uri })
        else if (singleSelection) onSelection(listOf(uri))
        else if (selected.size < 100) onSelection(selected + uri) else limit = true
    }
    LaunchedEffect(Unit) {
        controller.refresh()
        if (library.access() == GalleryAccess.Denied) permission.launch(LocalPhotoGallery.permissions)
    }
    LaunchedEffect(transition.currentState, transition.isIdle, closing) {
        if (closing && transition.isIdle && !transition.currentState) onDismiss()
    }
    DisposableEffect(controller, lifecycle, state.access) {
        var firstResume = true
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) { if (firstResume) firstResume = false else controller.refresh() }
            else if (event == Lifecycle.Event.ON_STOP) controller.stop()
        }
        lifecycle.addObserver(listener)
        val stop = runCatching { controller.observe { if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.changed() } }.getOrElse { {} }
        onDispose { stop(); lifecycle.removeObserver(listener) }
    }
    DisposableEffect(controller) { onDispose { controller.stop() } }
    LaunchedEffect(state.revision) {
        val snapshot = latestSelection.toList()
        val allowed = controller.accessible(snapshot).toSet()
        updateSelection(latestSelection.filter { it !in snapshot || it in allowed })
    }
    LaunchedEffect(state.folder?.key) { grid.scrollToItem(0) }
    LaunchedEffect(grid, controller) {
        snapshotFlow { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) to grid.layoutInfo.totalItemsCount }
            .distinctUntilChanged().collect { (last, count) -> if (count > 0 && last >= count - 12) controller.more() }
    }
    RegisterFloatingBackdrop()
    Dialog(onDismissRequest = ::dismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        FloatingBackdropHost {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .34f)).clickable(indication = null,
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) { dismiss() }) {
            AnimatedVisibility(transition, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(VectorMotionSpec.transition(appearance)) + slideInVertically(VectorMotionSpec.transition(appearance)) { (it * VectorMotionSpec.strength(appearance)).toInt() },
                exit = fadeOut(VectorMotionSpec.exit(appearance)) + slideOutVertically(VectorMotionSpec.exit(appearance)) { (it * VectorMotionSpec.strength(appearance)).toInt() }) {
                val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                Column(Modifier.fillMaxWidth().fillMaxHeight(.86f).clip(shape)
                    .background(colors.panel.copy(alpha = if (appearance.amoledBlack) 1f else .96f)).designFrame(shape)
                    .clickable(indication = null, interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }) {}
                    .navigationBarsPadding()) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            DefaultAction("${state.folder?.name ?: "All Photos"} · ${state.folder?.count ?: state.folders.firstOrNull()?.count ?: 0} ▾", { foldersOpen = true }, compact = true)
                            DefaultActionMenu(foldersOpen, { foldersOpen = false }) {
                                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                                    state.folders.forEach { folder -> DefaultAction("${folder.name} · ${folder.count}", {
                                        controller.folder(folder); foldersOpen = false
                                    }, Modifier.fillMaxWidth()) }
                                }
                            }
                        }
                        DefaultIconControl(Icons.Default.Close, "Close gallery", ::dismiss, enabled = !busy, quiet = true)
                    }
                    if (state.access != GalleryAccess.Full) Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (state.access == GalleryAccess.Limited) "Selected photos only" else "Allow access to local photos",
                            Modifier.weight(1f), color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                        DefaultAction(if (state.access == GalleryAccess.Limited) "Change access" else "Allow photos", { permission.launch(LocalPhotoGallery.permissions) }, compact = true)
                    }
                    if (state.access == GalleryAccess.Denied) DefaultAction("Photo access settings", {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                    }, Modifier.padding(horizontal = 12.dp), compact = true)
                    LazyVerticalGrid(GridCells.Fixed(3), state = grid, modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        items(state.photos, key = { it.uri }, contentType = { "photo" }) { photo ->
                            val bitmap by produceState<Bitmap?>(null, photo.uri, photo.modified, state.revision) {
                                value = controller.thumbnail(photo, state.revision)
                            }
                            val number = selected.indexOf(photo.uri) + 1
                            Box(Modifier.aspectRatio(1f).background(colors.raised).combinedClickable(onClick = {
                                if (!busy && !closing) {
                                    if (number == 0 && selected.size >= 100) limit = true
                                    else { if (number == 0) onSelection(if (singleSelection) listOf(photo.uri) else selected + photo.uri); onPreview(photo.uri) }
                                }
                            }, onLongClick = { toggle(photo.uri) })) {
                                bitmap?.let { Image(it.asImageBitmap(), "Local photo", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                                Box(Modifier.align(Alignment.TopEnd).size(48.dp).clickable(role = Role.Checkbox, enabled = !busy) { toggle(photo.uri) }
                                    .semantics { this.selected = number > 0; contentDescription = if (number > 0) "Selected photo $number" else "Select photo" }, contentAlignment = Alignment.Center) {
                                    Box(Modifier.size(26.dp).background(if (number > 0) colors.accent else Color.Black.copy(alpha = .55f), CircleShape), contentAlignment = Alignment.Center) {
                                        Text(if (number > 0) number.toString() else "+", color = if (number > 0) colors.onAccent else Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                        if (state.loading) item(span = { GridItemSpan(3) }) { Text("Loading local photos…", Modifier.padding(12.dp), color = colors.secondary) }
                        if (!state.loading && state.photos.isEmpty()) item(span = { GridItemSpan(3) }) {
                            Text(if (state.access == GalleryAccess.Limited) "No accessible photos in this folder. Change access or select All Photos." else "No accessible local photos in this folder.", Modifier.padding(16.dp), color = colors.secondary)
                        }
                        if (state.more && !state.loading) item(span = { GridItemSpan(3) }) { DefaultAction("Load more", controller::more, Modifier.fillMaxWidth()) }
                    }
                    (error ?: state.error)?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (state.error != null) DefaultAction("Refresh gallery", controller::refresh, Modifier.padding(horizontal = 12.dp), compact = true)
                    if (limit) Text("Select up to 100 photos per send.", Modifier.padding(horizontal = 12.dp), color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(if (singleSelection) "Profile photo" else "${selected.size} selected · ${quality.name}", Modifier.weight(1f), color = colors.secondary, style = MaterialTheme.typography.labelSmall)
                        DefaultAction(if (busy) "Preparing…" else if (singleSelection) "Crop" else "Send", onSend, emphasized = true, enabled = selected.isNotEmpty() && !busy && !uncertain && !closing)
                    }
                }
            }
        }
        }
    }
}
