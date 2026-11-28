package com.vector.messenger.calling

import android.app.NotificationManager
import android.content.*
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.os.Handler
import android.os.HandlerThread
import android.os.Vibrator
import android.os.VibrationEffect
import androidx.core.app.NotificationManagerCompat
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
        Start(R.raw.call_start), Waiting(R.raw.call_waiting, true), Ringback(R.raw.call_ringback, true), Connecting(R.raw.call_connecting, true),
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
    private var ringtone: Ringtone? = null
    private var incomingRequested = false
    private var incomingSilenced = false
    private var vibrating = false
    private val vibrator = context.getSystemService(Vibrator::class.java)
    private var lastBlock: String? = "uninitialized"
    private var reportedRoute = Int.MIN_VALUE
    @Volatile private var incomingWanted = false
    private var loopPlayer: MediaPlayer? = null
    private val ringAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
    private val modeListener = if (Build.VERSION.SDK_INT >= 31) AudioManager.OnModeChangedListener {
        handler.post { applyState() }
    } else null
    private val policy = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { handler.post {
            stopIncoming(); applyState()
        } }
    }
    internal fun begin(attempt: Long) {
        incomingWanted = false
        handler.post {
        if (token == attempt && pool != null) return@post
        release()
        token = attempt; initiated = false; connected = false; hadAudio = false; readyAt = 0; finished = false; tail = false
        latest = VoiceCallState(); cue = null; lastBlock = "uninitialized"; incomingSilenced = false; reportedRoute = Int.MIN_VALUE
        try {
            val created = SoundPool.Builder().setMaxStreams(1).setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()).build()
            pool = created
            created.setOnLoadCompleteListener { source, id, status -> handler.post {
                if (source === pool) {
                    val name = samples.entries.firstOrNull { it.value == id }?.key?.name ?: "unknown"
                    callAudioDiagnostic("CALL_ASSET_LOAD", "cue=$name status=$status")
                    if (status == 0) { loaded += id; if (!finished) applyState() }
                }
            } }
            Cue.entries.filterNot { it.loop }.forEach { samples[it] = created.load(context, it.resource, 1) }
            ContextCompat.registerReceiver(context, policy, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION).apply {
                addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            }, ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            if (Build.VERSION.SDK_INT >= 31 && modeListener != null)
                audio.addOnModeChangedListener({ command -> handler.post(command) }, modeListener)
        } catch (error: RuntimeException) { callAudioDiagnostic("CALL_PLAYER_ERROR", "operation=prepare exception=${error.javaClass.simpleName}"); release() }
    } }
    internal fun update(attempt: Long, state: VoiceCallState, effects: Boolean) {
        incomingWanted = state.phase == VoiceCallPhase.Incoming
        handler.post {
        if (attempt != token || finished) return@post
        latest = state; enabled = effects
        if (reportedRoute != state.audioOutput) {
            reportedRoute = state.audioOutput
            callAudioDiagnostic("CALL_AUDIO_ROUTE", "endpoint=${state.audioOutput} mode=${audio.mode} voiceVolume=${audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL)}")
        }
        if (state.audioReady && !hadAudio) readyAt = SystemClock.elapsedRealtime()
        hadAudio = hadAudio || state.audioReady
        applyState()
    } }
    private fun permitted(): Boolean = try {
        // A user-initiated outgoing call follows VOICE_CALL, not ringtone/DTMF policy.
        // audioReady is granted only after the existing Telecom/foreground setup completes.
        val reason = when {
            !enabled -> "effects_disabled"
            !latest.audioReady -> "telecom_audio_not_ready"
            audio.getStreamVolume(AudioManager.STREAM_VOICE_CALL) <= 0 -> "voice_volume_zero"
            else -> null
        }
        if (reason != lastBlock) {
            lastBlock = reason
            callAudioDiagnostic("CALL_AUDIO_FOCUS", "owner=Telecom sessionReady=${latest.audioReady} mode=${audio.mode} blocked=${reason ?: "none"}")
        }
        reason == null
    } catch (error: RuntimeException) {
        callAudioDiagnostic("CALL_PLAYER_ERROR", "operation=policy exception=${error.javaClass.simpleName}"); false
    }
    private fun incomingAllowed(): Boolean {
        val channel = notifications.getNotificationChannel(TelegramCallPlatform.RING)
        val display = notifications.getNotificationChannel(TelegramCallPlatform.DISPLAY)
        return NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (display == null || display.importance >= NotificationManager.IMPORTANCE_DEFAULT) &&
            (channel == null || channel.importance >= NotificationManager.IMPORTANCE_DEFAULT) &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            audio.ringerMode != AudioManager.RINGER_MODE_SILENT
    }
    private fun startIncoming() {
        if (incomingRequested) return
        incomingRequested = true
        callAudioDiagnostic("RINGTONE_START_REQUESTED", "mode=${audio.mode} ringer=${audio.ringerMode} ringVolume=${audio.getStreamVolume(AudioManager.STREAM_RING)}")
        try {
            if (!incomingAllowed()) {
                callAudioDiagnostic("RINGTONE_SUPPRESSED", "reason=notification_or_interruption_policy")
                return
            }
            val channel = notifications.getNotificationChannel(TelegramCallPlatform.RING)
            val sound = if (channel != null) channel.sound else RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val audible = sound != null && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL && audio.getStreamVolume(AudioManager.STREAM_RING) > 0
            if (!audible && channel?.shouldVibrate() != true) {
                callAudioDiagnostic("RINGTONE_SUPPRESSED", "reason=silent_channel_or_volume"); return
            }
            callAudioDiagnostic("CALL_AUDIO_FOCUS", "owner=Telecom incoming=true mode=${audio.mode} separateRequest=false")
            // Incoming vibration must not depend on a player or a competing focus request.
            if (channel?.shouldVibrate() == true && vibrator?.hasVibrator() == true && incomingWanted) {
                val pattern = channel.vibrationPattern?.takeIf { it.size > 1 && it.any { time -> time > 0 } } ?: longArrayOf(0, 350, 650)
                @Suppress("DEPRECATION")
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0), ringAttributes)
                vibrating = true
                callAudioDiagnostic("RINGTONE_VIBRATION_STARTED", "patternRequested=true")
            }
            if (audible) {
                val created = RingtoneManager.getRingtone(context, sound)
                    ?: if (sound != RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                        RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)) else null
                if (created == null) {
                    callAudioDiagnostic("RINGTONE_ERROR", "reason=resource_unavailable")
                } else {
                    if (!incomingWanted) { created.stop(); return }
                    ringtone = created
                    created.audioAttributes = ringAttributes
                    created.isLooping = true
                    created.play()
                    val owner = token
                    handler.postDelayed({
                        try {
                        if (owner == token && ringtone === created && incomingWanted && latest.phase == VoiceCallPhase.Incoming && !finished) {
                            if (created.isPlaying) callAudioDiagnostic("RINGTONE_PLAYING", "reportedPlaying=true looping=${created.isLooping}")
                            else {
                                callAudioDiagnostic("RINGTONE_ERROR", "reason=player_not_playing")
                                // An unreadable custom URI may use the system default; a user-silent URI never does.
                                val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                                if (sound != fallback) {
                                    created.stop()
                                    ringtone = RingtoneManager.getRingtone(context, fallback)?.apply {
                                        audioAttributes = ringAttributes; isLooping = true; play()
                                    }
                                    callAudioDiagnostic("RINGTONE_FALLBACK", "reportedPlaying=${ringtone?.isPlaying == true}")
                                }
                            }
                        }
                        } catch (error: RuntimeException) {
                            callAudioDiagnostic("RINGTONE_ERROR", "operation=verify exception=${error.javaClass.simpleName}")
                            stopIncoming(); incomingRequested = true
                        }
                    }, 150)
                }
            } else callAudioDiagnostic("RINGTONE_SUPPRESSED", "reason=silent_channel_or_volume vibrationOnly=true")
        } catch (error: RuntimeException) {
            callAudioDiagnostic("RINGTONE_ERROR", "exception=${error.javaClass.simpleName}")
            stopIncoming()
            incomingRequested = true // Never construct another player for each state/identity update.
        }
    }
    private fun stopIncoming() {
        val old = ringtone
        ringtone = null
        try { old?.stop() } catch (error: RuntimeException) { callAudioDiagnostic("RINGTONE_ERROR", "operation=stop exception=${error.javaClass.simpleName}") }
        if (vibrating) { try { vibrator?.cancel() } catch (_: RuntimeException) {}; vibrating = false; callAudioDiagnostic("RINGTONE_VIBRATION_STOPPED") }
        val stillPlaying = try { old?.isPlaying == true } catch (_: RuntimeException) { false }
        if (old != null || incomingRequested) callAudioDiagnostic("RINGTONE_STOPPED", "reportedPlaying=$stillPlaying")
        incomingRequested = false
    }
    internal fun stopIncomingCall() { incomingWanted = false; handler.post { incomingSilenced = true; stopIncoming() } }
    private fun applyState() {
        if (finished || pool == null) return
        if (latest.phase == VoiceCallPhase.Incoming) {
            stop()
            if (!incomingSilenced && incomingWanted) startIncoming() else stopIncoming()
            return
        }
        stopIncoming()
        // Never replay a delayed one-shot after policy changes or a slow sample load.
        val now = SystemClock.elapsedRealtime()
        if (hadAudio && now - readyAt > 600) initiated = true
        if (latest.connectedAt > 0 && now - latest.connectedAt > 600) connected = true
        if (!permitted()) { stop(); return }
        val desired = when {
            latest.phase == VoiceCallPhase.Active -> if (!connected || cue == Cue.Connected) Cue.Connected else null
            cue == Cue.Start && latest.phase in listOf(VoiceCallPhase.Requesting, VoiceCallPhase.Waiting, VoiceCallPhase.Ringing) -> Cue.Start
            latest.connectedAt > 0 -> null // Recovery never repeats Connected or talks over established media.
            latest.outgoing && !initiated && latest.phase in listOf(VoiceCallPhase.Requesting, VoiceCallPhase.Waiting, VoiceCallPhase.Ringing) -> Cue.Start
            latest.phase == VoiceCallPhase.Waiting && latest.outgoing -> Cue.Waiting
            latest.phase == VoiceCallPhase.Ringing && latest.outgoing -> Cue.Ringback
            latest.phase == VoiceCallPhase.Connecting -> Cue.Connecting
            else -> null
        }
        if (desired == cue) return
        stop()
        if (desired == null) return
        if (desired == Cue.Ringback || desired == Cue.Waiting) callAudioDiagnostic("RINGBACK_START_REQUESTED", "cue=${desired.name}")
        if (desired.loop) { startLoop(desired); return }
        val sample = samples[desired]?.takeIf { it in loaded } ?: run {
            callAudioDiagnostic("CALL_ASSET_PENDING", "cue=${desired.name}"); return
        }
        val played = pool?.play(sample, .5f, .5f, 1, if (desired.loop) -1 else 0, 1f) ?: 0
        if (played == 0) { callAudioDiagnostic("CALL_PLAYER_ERROR", "operation=SoundPool.play cue=${desired.name} streamAccepted=false"); return }
        stream = played; cue = desired
        callAudioDiagnostic(if (desired == Cue.Ringback || desired == Cue.Waiting) "RINGBACK_PLAYING" else "CALL_TONE_PLAYING",
            "cue=${desired.name} api=SoundPool streamAccepted=true audible=unverified")
        if (desired == Cue.Connected) connected = true
        if (desired == Cue.Start) {
            initiated = true
            val owner = token
            handler.postDelayed({ if (token == owner && !finished && cue == Cue.Start) { stop(); applyState() } }, 180)
        }
    }
    private fun startLoop(desired: Cue) {
        val player = MediaPlayer()
        loopPlayer = player; cue = desired
        try {
            player.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            context.resources.openRawResourceFd(desired.resource).use { source ->
                player.setDataSource(source.fileDescriptor, source.startOffset, source.length)
            }
            player.isLooping = true
            player.setVolume(1f, 1f)
            player.setOnPreparedListener {
                if (loopPlayer === player && cue == desired && !finished && latest.audioReady) {
                    player.start()
                    callAudioDiagnostic("RINGBACK_PLAYING", "cue=${desired.name} api=MediaPlayer reportedPlaying=${player.isPlaying} looping=${player.isLooping} routedDevice=${player.routedDevice?.type ?: -1}")
                }
            }
            player.setOnErrorListener { _, what, extra ->
                callAudioDiagnostic("CALL_PLAYER_ERROR", "api=MediaPlayer cue=${desired.name} what=$what extra=$extra")
                if (loopPlayer === player) stop()
                true
            }
            player.addOnRoutingChangedListener(AudioRouting.OnRoutingChangedListener { route ->
                if (loopPlayer === player) callAudioDiagnostic("CALL_AUDIO_ROUTE", "source=MediaPlayer routedDevice=${route.routedDevice?.type ?: -1}")
            }, handler)
            player.prepareAsync()
        } catch (error: Exception) {
            callAudioDiagnostic("CALL_PLAYER_ERROR", "operation=loop cue=${desired.name} exception=${error.javaClass.simpleName}")
            stop()
        }
    }
    private fun stop() {
        loopPlayer?.let { player ->
            loopPlayer = null
            try { player.stop() } catch (_: RuntimeException) {}
            player.release()
            callAudioDiagnostic("RINGBACK_STOPPED", "api=MediaPlayer cue=${cue?.name}")
        }
        if (stream != 0) {
            pool?.stop(stream)
            callAudioDiagnostic(if (cue == Cue.Ringback || cue == Cue.Waiting) "RINGBACK_STOPPED" else "CALL_TONE_STOPPED", "cue=${cue?.name ?: "terminal"}")
        }
        stream = 0; cue = null
    }
    internal fun finish(reason: CallToneEnd?) { incomingWanted = false; handler.post {
        if (pool == null || finished) return@post
        stopIncoming(); stop(); finished = true; tail = true
        val effect = when (reason) { CallToneEnd.Busy -> Cue.Busy; CallToneEnd.Ended -> Cue.Ended; CallToneEnd.Failed -> Cue.Failed; null -> null }
        val sample = effect?.let { samples[it] }?.takeIf { it in loaded }
        if (hadAudio && permitted() && sample != null) stream = pool?.play(sample, .5f, .5f, 1, 0, 1f) ?: 0
        val owner = token
        handler.postDelayed({ if (token == owner) release() }, if (stream != 0) 850 else 0)
    } }
    internal fun awaitTermination() { incomingWanted = false; handler.post {
        if (finished) return@post
        stopIncoming(); stop(); tail = true
        val owner = token
        handler.postDelayed({ if (token == owner && !finished) release() }, 1200)
    } }
    internal fun serviceStopped(attempt: Long) { handler.post { if (token == attempt && !tail) release() } }
    internal fun cancel() { incomingWanted = false; handler.post { release() } }
    private fun release() {
        stopIncoming(); stop(); pool?.setOnLoadCompleteListener(null); pool?.release(); pool = null
        samples.clear(); loaded.clear(); finished = true
        if (receiverRegistered) { try { context.unregisterReceiver(policy) } catch (_: IllegalArgumentException) {} }
        receiverRegistered = false
        if (Build.VERSION.SDK_INT >= 31 && modeListener != null) {
            try { audio.removeOnModeChangedListener(modeListener) } catch (_: RuntimeException) {}
        }
    }
}
