package com.vector.messenger.feature.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.vector.core.audio.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Single foreground microphone owner. Private, mono Ogg/Opus; no network or route override. */
@Singleton
class VoiceRecorder @Inject constructor(@ApplicationContext private val context: Context) : VoiceCapture {
    private val guard = Mutex()
    private val drafts = File(context.noBackupFilesDir, "voice-recordings")
    private val uploads = File(context.noBackupFilesDir, "telegram/files/outgoing-voice")
    private val audio = context.getSystemService(AudioManager::class.java)
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var started = 0L
    private var focus: AudioFocusRequest? = null
    private var cleaned = false
    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var routingCallback: AudioRouting.OnRoutingChangedListener? = null
    private fun pruneAbandonedDrafts() {
        if (!cleaned) {
            drafts.listFiles()?.filter { it.name.matches(Regex("[0-9a-f-]{36}\\.ogg")) }?.forEach { it.delete() }
            cleaned = true
        }
    }

    override suspend fun start(interrupted: (String) -> Unit) = withContext(Dispatchers.IO) { guard.withLock {
        check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
        check(recorder == null)
        check(audio.mode != AudioManager.MODE_IN_CALL && audio.mode != AudioManager.MODE_IN_COMMUNICATION)
        drafts.mkdirs()
        pruneAbandonedDrafts()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener({ change -> if (change < 0) interrupted("Recording interrupted by another audio activity.") }, Handler(Looper.getMainLooper())).build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus = request
        val target = File(drafts, "${UUID.randomUUID()}.ogg"); file = target
        @Suppress("DEPRECATION")
        val next = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        recorder = next
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.OGG)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.OPUS)
            next.setAudioChannels(1); next.setAudioSamplingRate(48000); next.setAudioEncodingBitRate(32000)
            next.setMaxDuration(600_000); next.setOutputFile(target.path)
            next.setOnErrorListener { _, _, _ -> interrupted("Microphone recording failed.") }
            next.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) interrupted("Recording reached the 10-minute limit. Record a shorter message.") }
            val callback = object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
                    if (configs.any { it.isClientSilenced }) interrupted("Microphone access was interrupted or muted by Android.")
                }
            }
            next.registerAudioRecordingCallback(context.mainExecutor, callback); recordingCallback = callback
            next.prepare(); next.start(); started = SystemClock.elapsedRealtime()
            var routeId = next.routedDevice?.id ?: 0
            val route = AudioRouting.OnRoutingChangedListener { router ->
                val incoming = router.routedDevice?.id ?: 0
                if (routeId != 0 && incoming != routeId) interrupted("Audio input changed. Recording was canceled.")
                routeId = incoming
            }
            next.addOnRoutingChangedListener(route, Handler(Looper.getMainLooper())); routingCallback = route
        } catch (failure: Exception) { release(); target.delete(); file = null; throw failure }
    } }
    override suspend fun meter(): Pair<Long, Int> = withContext(Dispatchers.IO) { guard.withLock {
        val current = recorder ?: return@withLock 0L to 0
        (SystemClock.elapsedRealtime() - started) to current.maxAmplitude.coerceIn(0, 32767)
    } }
    override suspend fun finish(amplitudes: List<Int>): VoiceClip = withContext(Dispatchers.IO) { guard.withLock {
        val current = recorder ?: error("No recording")
        val target = file ?: error("No recording")
        val duration = SystemClock.elapsedRealtime() - started
        try {
            current.stop()
            check(duration >= 800 && target.length() > 0)
            VoiceClip(target.path, kotlin.math.ceil(duration / 1000.0).toInt().coerceIn(1, 600), voiceWaveform(amplitudes))
        } catch (failure: Exception) { target.delete(); throw failure }
        finally { release(); file = null }
    } }
    override suspend fun cancel() = withContext(Dispatchers.IO) { guard.withLock {
        runCatching { recorder?.stop() }; release(); file?.delete(); file = null
        pruneAbandonedDrafts()
    } }
    private fun release() {
        routingCallback?.let { route -> runCatching { recorder?.removeOnRoutingChangedListener(route) } }; routingCallback = null
        recordingCallback?.let { callback -> runCatching { recorder?.unregisterAudioRecordingCallback(callback) } }; recordingCallback = null
        recorder?.release(); recorder = null
        focus?.let(audio::abandonAudioFocusRequest); focus = null
    }
    override suspend fun handoff(clip: VoiceClip): VoiceClip = withContext(Dispatchers.IO) {
        val original = File(clip.path).canonicalFile
        if (original.parentFile == uploads.canonicalFile) return@withContext clip
        check(original.parentFile == drafts.canonicalFile && original.isFile)
        uploads.mkdirs()
        val destination = File(uploads, original.name)
        check(original.renameTo(destination))
        clip.copy(path = destination.path)
    }
    override suspend fun discard(clip: VoiceClip?): Unit = withContext(Dispatchers.IO) {
        val candidate = clip?.path?.let { File(it).canonicalFile } ?: return@withContext
        check(candidate.parentFile == drafts.canonicalFile || candidate.parentFile == uploads.canonicalFile)
        candidate.delete()
        Unit
    }
}
