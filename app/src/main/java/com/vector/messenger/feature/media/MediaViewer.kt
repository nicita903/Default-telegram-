package com.vector.messenger.feature.media

import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.media.*
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance
import kotlinx.coroutines.*
import kotlin.math.*

@Composable
fun MediaViewer(items: List<MediaItem>, initialId: Long, viewModel: MediaViewModel, onClose: () -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var currentId by rememberSaveable(initialId, state.session) { mutableLongStateOf(initialId) }
    val item = items.find { it.messageId == currentId }
    LaunchedEffect(item, state.authenticated) { if (item == null || !state.authenticated) onClose() }
    if (item == null || !state.authenticated) return
    val file = state.files[item.media.file.id]
    val error by viewModel.error.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var controls by rememberSaveable(currentId) { mutableStateOf(true) }
    fun navigate(delta: Int) { adjacentMedia(items, currentId, delta)?.let { currentId = it.messageId } }
    LaunchedEffect(item.media.file.id, state.session) {
        item.media.thumbnail?.let { viewModel.preview(item.copy(media = item.media.copy(file = it, thumbnail = null))) }
        // Opening is an explicit request. Large animations never auto-download inline.
        viewModel.download(item)
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        Box(Modifier.fillMaxSize().designCanvas(DefaultDesign.colors.mediaCanvas).testTag("media_viewer")) {
            key(state.session, item.media.file.id) {
                when (item.media.kind) {
                    MediaKind.Photo -> ZoomPhoto(item, file, item.media.thumbnail?.let { state.files[it.id] }, state.session,
                        onTap = { controls = !controls }, onClose = onClose, onNavigate = ::navigate)
                    MediaKind.Video, MediaKind.Animation -> {
                        val path = file?.path
                        if (path != null && file.complete) {
                            if (item.media.mime == "image/gif") GifMedia(path)
                            else VideoMedia(path, item.media.file.id, item.media.kind == MediaKind.Animation)
                        } else MediaPlaceholder(item, item.media.thumbnail?.let { state.files[it.id] }, state.session)
                    }
                    MediaKind.Document -> Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(item.media.name, color = DefaultDesign.colors.text)
                        Text(item.media.mime, color = DefaultDesign.colors.secondary)
                        if (file?.complete == true && item.exportAllowed) DefaultAction("Open document", { viewModel.external(context, item, false) })
                    }
                    MediaKind.Voice, MediaKind.Audio -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        com.vector.messenger.feature.audio.VoiceAttachment(item, viewModel)
                    }
                }
            }
            if (controls || item.media.kind != MediaKind.Photo) {
                Row(Modifier.fillMaxWidth().safeDrawingPadding().align(Alignment.TopCenter).background(DefaultDesign.colors.mediaChrome.copy(alpha = .85f)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    DefaultIconControl(Icons.Default.Close, "Close media", onClose, quiet = true)
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(item.media.name, color = DefaultDesign.colors.text, maxLines = 1)
                        Text("${items.indexOf(item) + 1} / ${items.size} · ${fileSize(file?.size ?: item.media.file.size)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                    .background(DefaultDesign.colors.mediaChrome.copy(alpha = .9f)).padding(12.dp)) {
                    if (file?.active == true) {
                        LinearProgressIndicator(progress = { file.progress }, Modifier.fillMaxWidth(), color = DefaultDesign.colors.accent)
                        Text("${(file.progress * 100).toInt()}%", color = DefaultDesign.colors.secondary)
                    }
                    (error ?: state.error)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (file?.active == true) DefaultAction("Pause", { viewModel.pause(item.media.file.id) }, compact = true)
                        else if (file?.complete != true) DefaultAction("Download / retry", { viewModel.download(item) }, compact = true)
                        if (item.exportAllowed && file?.complete == true) {
                            DefaultAction("Open", { viewModel.external(context, item, false) }, compact = true)
                            DefaultAction("Share", { viewModel.external(context, item, true) }, compact = true)
                        }
                        if (items.size > 1) {
                            DefaultAction("Previous", { navigate(-1) }, enabled = adjacentMedia(items, currentId, -1) != null, compact = true)
                            DefaultAction("Next", { navigate(1) }, enabled = adjacentMedia(items, currentId, 1) != null, compact = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaPlaceholder(item: MediaItem, thumbnail: MediaFile?, session: Long) {
    val image by produceState<DecodedMediaImage?>(null, thumbnail?.path, session) {
        value = thumbnail?.path?.let { MediaImages.load(it, 1024, session) }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        image?.let { Image(it.bitmap.asImageBitmap(), item.media.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (image == null) Text(item.media.kind.name, color = DefaultDesign.colors.secondary)
    }
}

private data class ImageRegion(val image: androidx.compose.ui.graphics.ImageBitmap, val rect: Rect)

@Composable
private fun ZoomPhoto(item: MediaItem, file: MediaFile?, thumbnail: MediaFile?, session: Long,
    onTap: () -> Unit, onClose: () -> Unit, onNavigate: (Int) -> Unit) {
    val path = file?.path ?: thumbnail?.path
    val decoded by produceState<DecodedMediaImage?>(null, path, session) { value = path?.let { MediaImages.load(it, 2048, session) } }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var pull by remember { mutableStateOf(Offset.Zero) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val density = LocalDensity.current.density
    val slop = LocalViewConfiguration.current.touchSlop
    val returnedX = rememberInteractiveOffset({ pull.x }, { pull != Offset.Zero })
    val returnedY = rememberInteractiveOffset({ pull.y }, { pull != Offset.Zero })
    val currentTap by rememberUpdatedState(onTap)
    val currentClose by rememberUpdatedState(onClose)
    val currentNavigate by rememberUpdatedState(onNavigate)
    val width = item.media.width.takeIf { it > 0 } ?: decoded?.width ?: 1
    val height = item.media.height.takeIf { it > 0 } ?: decoded?.height ?: 1
    val fit = if (viewport.width == 0 || viewport.height == 0) 1f else min(viewport.width.toFloat() / width, viewport.height.toFloat() / height)
    val maximumZoom = max(4f, 1f / fit).coerceAtMost(64f)
    fun bounded(value: Offset, scale: Float): Offset {
        val x = max(0f, (width * fit * scale - viewport.width) / 2)
        val y = max(0f, (height * fit * scale - viewport.height) / 2)
        return Offset(value.x.coerceIn(-x, x), value.y.coerceIn(-y, y))
    }
    // A sampled whole-image preview plus a bounded original-resolution visible tile.
    // Region decode waits for a short quiet interval; dragging never decodes on Main.
    val originalPath = file?.path
    val region by produceState<ImageRegion?>(null, originalPath, zoom, pan, viewport) {
        value = null
        if (originalPath != null && zoom > 1.25f && viewport.width > 0) {
            delay(90)
            val factor = fit * zoom
            val left = (viewport.width - width * factor) / 2 + pan.x
            val top = (viewport.height - height * factor) / 2 + pan.y
            val rect = Rect(floor(-left / factor).toInt().coerceIn(0, width), floor(-top / factor).toInt().coerceIn(0, height),
                ceil((viewport.width - left) / factor).toInt().coerceIn(0, width), ceil((viewport.height - top) / factor).toInt().coerceIn(0, height))
            if (!rect.isEmpty) value = withContext(Dispatchers.IO) {
                @Suppress("DEPRECATION")
                val decoder = runCatching { BitmapRegionDecoder.newInstance(originalPath, false) }.getOrNull()
                try {
                    var sample = 1
                    while (sample * 2 <= 1 / factor || rect.width().toLong() / sample * (rect.height() / sample) > 4_000_000) sample *= 2
                    decoder?.decodeRegion(rect, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()?.let { ImageRegion(it, rect) }
                } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
                finally { decoder?.recycle() }
            }
        }
    }
    Box(Modifier.fillMaxSize().onSizeChanged { viewport = it }.pointerInput(item.media.file.id, viewport, width, height) {
        var lastTap = 0L
        var lastPosition = Offset.Zero
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = true)
            if (down.position.x < 24 * density || down.position.x > size.width - 24 * density) return@awaitEachGesture
            var multi = false
            var cancelled = false
            var moved = false
            var delta = Offset.Zero
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    if (event.changes.any { it.isConsumed }) { cancelled = true; break }
                    val pressed = event.changes.count { it.pressed }
                    if (pressed > 1) multi = true
                    if (pressed == 0) {
                        if (!moved && !multi && event.changes.size == 1) {
                            val up = event.changes.single()
                            if (up.uptimeMillis - lastTap < 300 && (down.position - lastPosition).getDistance() < 32 * density) {
                                val next = if (zoom > 1.01f) 1f else min(maximumZoom, 3f)
                                pan = if (next == 1f) Offset.Zero else bounded((Offset(size.width / 2f, size.height / 2f) - down.position) * (next - 1), next)
                                zoom = next; lastTap = 0
                            } else { currentTap(); lastTap = up.uptimeMillis; lastPosition = down.position }
                            up.consume()
                        } else {
                            when (viewerRelease(delta.x, delta.y, zoom, multi, cancelled, density)) {
                                ViewerAction.Dismiss -> currentClose()
                                ViewerAction.Next -> currentNavigate(1)
                                ViewerAction.Previous -> currentNavigate(-1)
                                else -> Unit
                            }
                        }
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null && !multi) { cancelled = true; break }
                    if (multi || zoom > 1.01f) {
                        val next = (zoom * event.calculateZoom()).coerceIn(1f, maximumZoom)
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val centroid = event.calculateCentroid(useCurrent = false).takeIf { it.x.isFinite() } ?: center
                        pan = bounded((pan - (centroid - center)) * (next / zoom) + (centroid - center) + event.calculatePan(), next)
                        zoom = next; pull = Offset.Zero; moved = true
                        event.changes.forEach { it.consume() }
                    } else if (change != null) {
                        delta = change.position - down.position
                        if (delta.getDistance() > slop) moved = true
                        if (moved) {
                            pull = Offset(delta.x * .3f, max(0f, delta.y) * .65f)
                            change.consume()
                        }
                    }
                }
            } finally { pull = Offset.Zero }
        }
    }) {
        val image = decoded
        if (image == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(if (path == null) "Loading photo…" else "Preview unavailable. Download again or open externally.", color = DefaultDesign.colors.secondary)
        } else Image(image.bitmap.asImageBitmap(), item.media.name, Modifier.fillMaxSize().graphicsLayer {
            scaleX = zoom; scaleY = zoom
            translationX = pan.x + returnedX.value; translationY = pan.y + returnedY.value
        }, contentScale = ContentScale.Fit)
        region?.let { tile -> Canvas(Modifier.fillMaxSize()) {
            val factor = fit * zoom
            val left = (size.width - width * factor) / 2 + pan.x
            val top = (size.height - height * factor) / 2 + pan.y
            drawImage(tile.image, dstOffset = IntOffset((left + tile.rect.left * factor).roundToInt(), (top + tile.rect.top * factor).roundToInt()),
                dstSize = IntSize((tile.rect.width() * factor).roundToInt().coerceAtLeast(1), (tile.rect.height() * factor).roundToInt().coerceAtLeast(1)))
        } }
        if (zoom > 1.01f) Text("${String.format(java.util.Locale.ROOT, "%.1f", zoom)}×", Modifier.align(Alignment.CenterEnd).padding(16.dp), color = DefaultDesign.colors.secondary)
    }
}


