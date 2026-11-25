package com.vector.messenger.calling

import android.app.NotificationManager
import android.content.*
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.vector.messenger.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

internal enum class CallToneEnd { Busy, Ended, Failed }

/** One idle worker; each real foreground call owns one asynchronously loaded pool, then releases it. */
@Singleton
class CallSoundController @Inject constructor(@ApplicationContext private val context: Context) {
    private enum class Cue(val resource: Int, val loop: Boolean = false) {
        Start(R.raw.call_start), Ringback(R.raw.call_ringback, true), Connecting(R.raw.call_connecting, true),
        Connected(R.raw.call_connected), Busy(R.raw.call_busy), Ended(R.raw.call_ended), Failed(R.raw.call_failed)
    }
    private val worker = HandlerThread("Default call tones", android.os.Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
    private val handler = Handler(worker.looper)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    // Everything below is confined to the worker, including SoundPool callbacks.
    private var pool: SoundPool? = null
    private val samples = mutableMapOf<Cue, Int>()
    private val loaded = mutableSetOf<Int>()
    private var token = -1L
    private var stream = 0
    private var cue: Cue? = null
    private var latest = VoiceCallState()
    private var enabled = true
    private var initiated = false
    private var connected = false
    private var hadAudio = false
    private var readyAt = 0L
    private var finished = false
    private var tail = false
    private var receiverRegistered = false
    private val modeListener = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnModeChangedListener {
        handler.post { if (!permitted()) stop() else applyState() }
    } else null
    private val policy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { handler.post { if (!permitted()) stop() else applyState() } }
    }
    internal fun begin(attempt: Long) { handler.post {
        if (token == attempt && pool != null) return@post
        release()
        token = attempt; initiated = false; connected = false; hadAudio = false; readyAt = 0; finished = false; tail = false
        latest = VoiceCallState(); cue = null
        try {
            val created = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = created
            created.setOnLoadCompleteListener { source, id, status -> handler.post {
                if (source === pool && status == 0) { loaded += id; if (!finished) applyState() }
            } }
            Cue.entries.forEach { samples[it] = created.load(context, it.resource, 1) }
            ContextCompat.registerReceiver(context, policy, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION).apply {
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            if (Build.VERSION.SDK_INT >= 31 && modeListener != null)
                audio.addOnModeChangedListener({ command -> handler.post(command) }, modeListener)
        } catch (_: RuntimeException) { release() }
    } }
    internal fun update(attempt: Long, state: VoiceCallState, effects: Boolean) { handler.post {
        if (attempt != token || finished) return@post
        latest = state; enabled = effects
        if (state.audioReady && !hadAudio) readyAt = SystemClock.elapsedRealtime()
        hadAudio = hadAudio || state.audioReady
        applyState()
    } }
    private fun permitted(): Boolean = try {
        enabled && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) > 0 &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            audio.mode in listOf(AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_IN_CALL)
    } catch (_: RuntimeException) { false }
    private fun applyState() {
        if (finished || pool == null) return
        // Never replay a delayed one-shot after policy changes or a slow sample load.
        val now = SystemClock.elapsedRealtime()
        if (hadAudio && now - readyAt > 600) initiated = true
        if (latest.connectedAt > 0 && now - latest.connectedAt > 600) connected = true
        if (!latest.audioReady || !permitted()) { stop(); return }
        val desired = when {
            latest.phase == VoiceCallPhase.Active -> if (!connected || cue == Cue.Connected) Cue.Connected else null
            cue == Cue.Start && latest.phase in listOf(VoiceCallPhase.Requesting, VoiceCallPhase.Ringing) -> Cue.Start
            latest.connectedAt > 0 -> null // Recovery never repeats Connected or talks over established media.
            latest.outgoing && !initiated && latest.phase in listOf(VoiceCallPhase.Requesting, VoiceCallPhase.Ringing) -> Cue.Start
            latest.phase == VoiceCallPhase.Ringing && latest.outgoing -> Cue.Ringback
            latest.phase == VoiceCallPhase.Connecting -> Cue.Connecting
            else -> null
        }
        if (desired == cue) return
        stop()
        if (desired == null) return
        val sample = samples[desired]?.takeIf { it in loaded } ?: return
        val played = pool?.play(sample, .5f, .5f, 1, if (desired.loop) -1 else 0, 1f) ?: 0
        if (played == 0) return
        stream = played; cue = desired
        if (desired == Cue.Connected) connected = true
        if (desired == Cue.Start) {
            initiated = true
            val owner = token
            handler.postDelayed({ if (token == owner && !finished && cue == Cue.Start) { stop(); applyState() } }, 180)
        }
    }
    private fun stop() { if (stream != 0) pool?.stop(stream); stream = 0; cue = null }
    internal fun finish(reason: CallToneEnd?) { handler.post {
        if (pool == null || finished) return@post
        stop(); finished = true; tail = true
        val effect = when (reason) { CallToneEnd.Busy -> Cue.Busy; CallToneEnd.Ended -> Cue.Ended; CallToneEnd.Failed -> Cue.Failed; null -> null }
        val sample = effect?.let { samples[it] }?.takeIf { it in loaded }
        if (hadAudio && permitted() && sample != null) stream = pool?.play(sample, .5f, .5f, 1, 0, 1f) ?: 0
        val owner = token
        handler.postDelayed({ if (token == owner) release() }, if (stream != 0) 850 else 0)
    } }
    internal fun awaitTermination() { handler.post {
        if (finished) return@post
        stop(); tail = true
        val owner = token
        handler.postDelayed({ if (token == owner && !finished) release() }, 1200)
    } }
    internal fun serviceStopped(attempt: Long) { handler.post { if (token == attempt && !tail) release() } }
    internal fun cancel() { handler.post { release() } }
    private fun release() {
        stop(); pool?.setOnLoadCompleteListener(null); pool?.release(); pool = null
        samples.clear(); loaded.clear(); finished = true
        if (receiverRegistered) { try { context.unregisterReceiver(policy) } catch (_: IllegalArgumentException) {} }
        receiverRegistered = false
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null) {
            try { audio.removeOnModeChangedListener(modeListener) } catch (_: RuntimeException) {}
        }
    }
}
