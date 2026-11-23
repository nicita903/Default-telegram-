package com.vector.messenger.feature.media

import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.widget.ImageView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.*
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.ui.PlayerView
import com.vector.messenger.design.*
import java.io.File
import kotlinx.coroutines.*

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
internal fun VideoMedia(path: String, fileId: Int, looping: Boolean, contentPadding: PaddingValues = PaddingValues(top = 80.dp, bottom = 145.dp), viewportModifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var savedPosition by rememberSaveable(fileId) { mutableLongStateOf(0) }
    var muted by rememberSaveable(fileId) { mutableStateOf(looping) }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(savedPosition) }
    var duration by remember { mutableLongStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    var seeking by remember { mutableStateOf(false) }
    var seekTarget by remember { mutableFloatStateOf(0f) }
    val fallbackScope = rememberCoroutineScope()
    val providedEngine = com.vector.messenger.feature.audio.LocalAudioViewModel.current?.engine
    val engine = remember(providedEngine, context) { providedEngine ?: com.vector.messenger.feature.audio.SharedMediaPlayer(context, fallbackScope) }
    val player = remember(path, engine) { engine.video(path, savedPosition, looping, muted) }
    DisposableEffect(player, lifecycle) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onEvents(player: Player, events: Player.Events) {
                duration = player.duration.coerceAtLeast(0)
                position = player.currentPosition.coerceAtLeast(0)
            }
            override fun onPlayerError(error: PlaybackException) { failed = true; playing = false }
        }
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { savedPosition = player.currentPosition; player.pause() }
        }
        player.addListener(listener); lifecycle.addObserver(observer)
        onDispose {
            savedPosition = player.currentPosition
            lifecycle.removeObserver(observer); player.removeListener(listener)
            engine.releaseVideo(player)
        }
    }
    LaunchedEffect(player, playing) {
        while (playing && isActive) {
            position = player.currentPosition.coerceAtLeast(0); savedPosition = position
            duration = player.duration.coerceAtLeast(0)
            delay(250) // Playback clock only; file progress is event-driven.
        }
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; useController = false } },
                update = { it.player = player }, modifier = Modifier.fillMaxSize(), onRelease = { it.player = null })
            Box(Modifier.matchParentSize().then(viewportModifier))
        }
        if (failed) Text("This media format cannot be played. Try opening it in another application.", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(horizontal = 20.dp)) {
            Slider(value = if (seeking) seekTarget else position.toFloat().coerceIn(0f, duration.toFloat().coerceAtLeast(1f)),
                onValueChange = { seeking = true; seekTarget = it }, onValueChangeFinished = { player.seekTo(seekTarget.toLong()); position = seekTarget.toLong(); savedPosition = position; seeking = false },
                valueRange = 0f..duration.toFloat().coerceAtLeast(1f), enabled = duration > 0 && !failed)
            Text("${playbackTime(if (seeking) seekTarget.toLong() else position)} / ${playbackTime(duration)}", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DefaultAction(if (playing) "Pause" else "Play", {
                    if (playing) player.pause() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        if (failed) { failed = false; player.prepare() }
                        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
                        player.play()
                    }
                }, compact = true)
                DefaultAction(if (muted) "Unmute" else "Mute", { muted = !muted; player.volume = if (muted) 0f else 1f }, compact = true)
            }
        }
    }
}

private fun playbackTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(java.util.Locale.ROOT, seconds / 60, seconds % 60)
}

@Composable
internal fun GifMedia(path: String) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var playing by remember { mutableStateOf(false) }
    val drawable by produceState<Drawable?>(null, path) {
        value = withContext(Dispatchers.IO) {
            try {
                ImageDecoder.decodeDrawable(ImageDecoder.createSource(File(path))) { decoder, info, _ ->
                    val scale = minOf(1.0, 1024.0 / maxOf(info.size.width, info.size.height))
                    decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
                }
            } catch (_: Exception) { null } catch (_: OutOfMemoryError) { null }
        }
    }
    val animated = drawable as? AnimatedImageDrawable
    DisposableEffect(animated, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) { animated?.stop(); playing = false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); animated?.stop() }
    }
    Box(Modifier.fillMaxSize().padding(top = 80.dp, bottom = 145.dp)) {
        AndroidView(factory = { ImageView(it).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
            update = { if (it.drawable !== drawable) it.setImageDrawable(drawable) }, modifier = Modifier.fillMaxSize(),
            onRelease = { animated?.stop(); it.setImageDrawable(null) })
        if (drawable == null) Text("Animation unavailable", color = DefaultDesign.colors.secondary, modifier = Modifier.align(Alignment.Center))
        else DefaultAction(if (playing) "Pause animation" else "Play animation", {
            if (playing) { animated?.stop(); playing = false }
            else if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) { animated?.start(); playing = animated != null }
        }, Modifier.align(Alignment.BottomCenter), enabled = animated != null, compact = true)
    }
}
