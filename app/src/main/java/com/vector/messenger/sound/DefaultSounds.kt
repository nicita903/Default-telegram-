package com.vector.messenger.sound

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import com.vector.core.*
import com.vector.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

/** One process-owned worker and one foreground pool. No audio focus, media player, or per-frame work. */
@Singleton
class DefaultSounds @Inject constructor(@ApplicationContext private val context: Context, private val settings: SoundPreferences,
    repository: ChatRepository, scope: CoroutineScope) {
    enum class Cue(val resource: Int) {
        Send(R.raw.default_send), Receive(R.raw.default_receive), Error(R.raw.default_error),
        Menu(R.raw.default_menu), Module(R.raw.default_module), Confirm(R.raw.default_confirm)
    }
    private data class View(val chat: Long?, val session: Long, val since: Long)
    @Volatile private var foreground = false
    @Volatile private var recording = false
    @Volatile private var view = View(null, 0, 0)
    private val worker = HandlerThread("Default sound effects", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(worker.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    // The fields below are confined to the worker, including load-completion callbacks.
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<Cue, Int>()
    private val loaded = mutableSetOf<Int>()
    private val seen = linkedSetOf<String>()
    private var session = -1L
    private var playedAt = -1000L
    init {
        scope.launch { repository.feedback.collect { event ->
            if (!repository.status.value.live || repository.status.value.session != event.session) return@collect
            val current = view
            if (current.session != event.session) return@collect
            val cue = when (event.kind) {
                ChatFeedbackKind.Accepted -> Cue.Send
                ChatFeedbackKind.Failed -> Cue.Error
                ChatFeedbackKind.Confirmation -> Cue.Confirm
                ChatFeedbackKind.Incoming -> {
                    if (current.chat != event.chatId || event.date <= 0 || event.date < current.since || event.date < System.currentTimeMillis() / 1000 - 15 || repository.status.value.connection != com.vector.core.profile.ProfileConnection.Ready) return@collect
                    Cue.Receive
                }
            }
            play(cue, "${event.session}:${event.chatId}:${event.key}", event.session)
        } }
    }
    fun foreground(value: Boolean) {
        if (value && !foreground) view = view.copy(since = System.currentTimeMillis() / 1000)
        foreground = value
        handler.post { if (value) prepare() else release() }
    }
    fun recording(value: Boolean) { recording = value }
    fun conversation(chat: Long?, session: Long) {
        val old = view
        if (old.chat != chat || old.session != session) view = View(chat, session, System.currentTimeMillis() / 1000)
    }
    fun menu() = play(Cue.Menu)
    fun module() = play(Cue.Module)
    private fun prepare() {
        if (!foreground || pool != null) return
        try {
            val created = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = created
            created.setOnLoadCompleteListener { source, id, status -> handler.post { if (source === pool && status == 0) loaded += id } }
            Cue.entries.forEach { samples[it] = created.load(context, it.resource, 1) }
        } catch (_: Exception) { release(); android.util.Log.w("DefaultSounds", "Sound samples unavailable") }
    }
    private fun release() {
        pool?.setOnLoadCompleteListener(null); pool?.release(); pool = null; samples.clear(); loaded.clear()
    }
    private fun play(cue: Cue, key: String? = null, epoch: Long = view.session) {
        if (!foreground || recording) return
        handler.post {
            if (session != epoch) { seen.clear(); session = epoch }
            if (key != null && !seen.add(key)) return@post
            while (seen.size > 512) seen.remove(seen.first())
            val value = settings.state.value
            val allowed = when (cue) {
                Cue.Send -> value.messages && value.send
                Cue.Receive -> value.messages && value.receive
                Cue.Error -> value.messages && value.errors
                else -> value.interfaceSounds
            }
            if (!allowed || value.volume == 0 || !foreground || recording) return@post
            try {
                if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL || audio.mode != AudioManager.MODE_NORMAL || audio.isMusicActive ||
                    audio.isStreamMute(AudioManager.STREAM_SYSTEM) || audio.getStreamVolume(AudioManager.STREAM_SYSTEM) == 0 ||
                    notifications.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL) return@post
                val now = SystemClock.elapsedRealtime()
                if (now - playedAt < 140) return@post
                val sample = samples[cue]?.takeIf { it in loaded } ?: return@post // Never replay stale sounds after loading.
                val level = value.volume / 100f
                if ((pool?.play(sample, level, level, 1, 0, 1f) ?: 0) != 0) playedAt = now
            } catch (_: Exception) { android.util.Log.w("DefaultSounds", "Sound feedback skipped by audio policy") }
        }
    }
}
