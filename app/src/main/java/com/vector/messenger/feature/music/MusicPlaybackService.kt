package com.vector.messenger.feature.music

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.*
import androidx.media3.session.*
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vector.messenger.MainActivity
import com.vector.messenger.feature.audio.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@EntryPoint
@InstallIn(SingletonComponent::class)
interface MusicPlaybackEntryPoint { fun coordinator(): MusicCoordinator }

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicPlaybackService : MediaSessionService() {
    private val coordinator by lazy { EntryPointAccessors.fromApplication(applicationContext, MusicPlaybackEntryPoint::class.java).coordinator() }
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        val player = coordinator.engine.musicPlayer.value
        if (player == null) { stopSelf(); return }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .putExtra("open_music_player", true).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).setCallback(object : MediaSession.Callback {
            override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
                if (controller.packageName != packageName && !controller.isTrusted && !session.isMediaNotificationController(controller)) return MediaSession.ConnectionResult.reject()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session).setAvailablePlayerCommands(
                    Player.Commands.Builder().addAllCommands().remove(Player.COMMAND_CHANGE_MEDIA_ITEMS).remove(Player.COMMAND_SET_MEDIA_ITEM).build()).build()
            }
            override fun onAddMediaItems(session: MediaSession, controller: MediaSession.ControllerInfo, items: List<MediaItem>): ListenableFuture<List<MediaItem>> {
                val active = session.player
                val known = (0 until active.mediaItemCount).associate { active.getMediaItemAt(it).mediaId to active.getMediaItemAt(it) }
                return Futures.immediateFuture(items.mapNotNull { known[it.mediaId] })
            }
        }).build()
        addSession(requireNotNull(session))
        scope.launch { coordinator.engine.musicPlayer.collect { active ->
            if (active == null) { session?.let { removeSession(it); it.release() }; session = null; stopSelf() }
            else if (session?.player !== active) session?.setPlayer(active)
        } }
        scope.launch { coordinator.state.map { it.phase }.distinctUntilChanged().collectLatest {
            if (it == AudioPhase.Paused || it == AudioPhase.Complete || it == AudioPhase.Failed) {
                delay(30_000); coordinator.engine.releasePausedMusic(); stopSelf()
            }
        } }
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: Intent?) { if (coordinator.engine.musicPlayer.value?.playWhenReady != true) { coordinator.engine.releasePausedMusic(); stopSelf() } }
    override fun onDestroy() { session?.release(); session = null; scope.cancel(); if (coordinator.engine.musicActive) coordinator.engine.stopMusic(); super.onDestroy() }
}
