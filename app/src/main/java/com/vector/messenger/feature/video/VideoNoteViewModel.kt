package com.vector.messenger.feature.video

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.*
import com.vector.core.*
import com.vector.core.media.*
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.feature.audio.SharedMediaPlayer
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

val LocalVideoNotes = androidx.compose.runtime.staticCompositionLocalOf<VideoNoteViewModel?> { null }
enum class VideoNotePhase { Idle, Starting, Recording, Paused, Encoding, Preview, Sending, Failed, Uncertain }
data class VideoNoteState(val chat: Long = 0, val phase: VideoNotePhase = VideoNotePhase.Idle, val locked: Boolean = false,
    val millis: Long = 0, val path: String? = null, val error: String? = null, val front: Boolean = true)

@HiltViewModel
class VideoNoteViewModel @Inject constructor(@ApplicationContext private val context: Context, private val chats: ChatRepository,
    private val profile: ProfileRepository, private val ghost: GhostRepository, private val engine: SharedMediaPlayer,
    val playback: VideoNotePlayback) : ViewModel() {
    private val prefs = context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    private val modeState = MutableStateFlow(prefs.getBoolean("videoNotes.mode", false))
    val videoMode = modeState.asStateFlow()
    private val autoplayState = MutableStateFlow(prefs.getBoolean("videoNotes.autoplay", false))
    val autoplay = autoplayState.asStateFlow()
    private val mutable = MutableStateFlow(VideoNoteState())
    val state = mutable.asStateFlow()
    private val acceptedEvents = MutableSharedFlow<Pair<Long, Long?>>(extraBufferCapacity = 2)
    val accepted = acceptedEvents.asSharedFlow()
    private var provider: ProcessCameraProvider? = null
    private var preview: Preview? = null
    private var capture: VideoCapture<Recorder>? = null
    private var active: Recording? = null
    private var clip: VideoNoteClip? = null
    private var raw: File? = null
    private var reply: Long? = null
    private var autoSend = false
    private var cancelled = false
    private var generation = 0L
    private var account = 0L
    private val accountState = MutableStateFlow(0L)
    val accountId = accountState.asStateFlow()
    private var encoded: File? = null
    val captureRetained get() = raw != null
    private var work: Job? = null
    private var actions: Job? = null
    private val sentFiles = mutableMapOf<Int, VideoNoteClip>()
    private val results = linkedMapOf<Int, Delivery>()
    private var restoreChat = 0L
    fun mode(video: Boolean) { if (state.value.phase != VideoNotePhase.Idle) return; modeState.value = video; prefs.edit().putBoolean("videoNotes.mode", video).apply() }
    fun autoplay(value: Boolean) { autoplayState.value = value; prefs.edit().putBoolean("videoNotes.autoplay", value).apply() }
    init {
        viewModelScope.launch { profile.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged().collect { id ->
            if (account != 0L && account != id) {
                if (state.value.phase in listOf(VideoNotePhase.Sending, VideoNotePhase.Uncertain)) {
                    generation++; cancelled = true; work?.cancel(); active?.stop(); active = null; detach(); clip = null; mutable.value = VideoNoteState()
                } else cancel()
            }
            account = id ?: 0; accountState.value = account; restoreChat = 0
        } }
        viewModelScope.launch { chats.outgoingUpdates.collect { message ->
            val id = message.media?.file?.id ?: return@collect
            results[id] = message.delivery; while (results.size > 64) results.remove(results.keys.first())
            if (message.delivery in listOf(Delivery.Sent, Delivery.Read)) sentFiles.remove(id)?.let { discard(it) }
        } }
    }
    private fun directory(): File = File(context.noBackupFilesDir, "telegram/files/video_notes_outbox/$account").apply { mkdirs() }
    private fun journal() = File(context.noBackupFilesDir, "video-note-draft-$account.json")
    private suspend fun save(clip: VideoNoteClip, pending: Boolean = false) = withContext(Dispatchers.IO) {
        val file = journal(); val atomic = android.util.AtomicFile(file); val out = atomic.startWrite()
        try { out.write(JSONObject().put("path", clip.path).put("chat", state.value.chat).put("pending", pending).put("reply", reply).toString().toByteArray()); atomic.finishWrite(out) }
        catch (error: Exception) { atomic.failWrite(out); throw error }
    }
    fun restore(chat: Long) {
        if (account == 0L || restoreChat == chat || state.value.phase != VideoNotePhase.Idle) return
        restoreChat = chat
        viewModelScope.launch {
            val owner = account
            try {
                val value = withContext(Dispatchers.IO) { journal().takeIf { it.isFile }?.readText()?.let(::JSONObject) } ?: return@launch
                if (value.optLong("chat") != chat) return@launch
                val file = withContext(Dispatchers.IO) { File(value.getString("path")).canonicalFile.also { require(it.toPath().startsWith(directory().canonicalFile.toPath())) } }
                val restored = validateVideoNote(file)
                if (account != owner) return@launch
                clip = restored; reply = value.optLong("reply").takeIf { it != 0L }
                mutable.value = VideoNoteState(chat, if (value.optBoolean("pending")) VideoNotePhase.Uncertain else VideoNotePhase.Preview,
                    locked = true, millis = restored.duration * 1000L, path = restored.path,
                    error = if (value.optBoolean("pending")) "A previous send may still be pending. Check the conversation before discarding; no automatic resend." else null)
            } catch (_: Exception) { /* A missing draft never blocks the chat. */ }
        }
    }
    fun start(chat: Long, replyTo: Long?, locked: Boolean = false) {
        if (account == 0L || state.value.phase != VideoNotePhase.Idle) return
        if (listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) {
            mutable.value = VideoNoteState(chat, VideoNotePhase.Failed, error = "Camera and microphone access are required."); return
        }
        generation++; cancelled = false; autoSend = false; reply = replyTo
        engine.stop(); playback.stop()
        mutable.value = VideoNoteState(chat, VideoNotePhase.Starting, locked)
    }
    fun attach(owner: LifecycleOwner, view: PreviewView) {
        if (state.value.phase !in listOf(VideoNotePhase.Starting, VideoNotePhase.Recording, VideoNotePhase.Paused)) return
        val token = generation
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (token != generation || cancelled) return@addListener
            try {
                val cameras = future.get(); provider = cameras
                preview?.let { cameras.unbind(it) }; capture?.let { cameras.unbind(it) }
                val live = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                val video = capture ?: VideoCapture.withOutput(Recorder.Builder().setQualitySelector(QualitySelector.from(Quality.SD, FallbackStrategy.higherQualityOrLowerThan(Quality.SD))).build())
                preview = live; capture = video; video.targetRotation = view.display?.rotation ?: android.view.Surface.ROTATION_0
                val selector = if (state.value.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cameras.bindToLifecycle(owner, selector, live, video)
                if (active == null && state.value.phase == VideoNotePhase.Starting) begin(video, token)
            } catch (_: Exception) { cancel("Camera unavailable. Check access or try the other camera.") }
        }, ContextCompat.getMainExecutor(context))
    }
    @androidx.annotation.OptIn(androidx.camera.video.ExperimentalPersistentRecording::class)
    @android.annotation.SuppressLint("MissingPermission")
    private fun begin(video: VideoCapture<Recorder>, token: Long) {
        work = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { File(directory(), UUID.randomUUID().toString() + "-capture.mp4") }
                if (token != generation || cancelled) return@launch
                raw = file
                active = video.output.prepareRecording(context, FileOutputOptions.Builder(file).setDurationLimitMillis(60_000).build())
                    .withAudioEnabled().asPersistentRecording().start(ContextCompat.getMainExecutor(context)) { event ->
                        if (token != generation) { if (event is VideoRecordEvent.Finalize && cancelled) file.delete(); return@start }
                        when (event) {
                            is VideoRecordEvent.Start -> {
                                mutable.update { it.copy(phase = VideoNotePhase.Recording) }
                                actions = viewModelScope.launch { while (isActive) { ghost.sendAction(state.value.chat, "chatActionRecordingVideoNote"); delay(4000) } }
                            }
                            is VideoRecordEvent.Status -> {
                                val millis = event.recordingStats.recordedDurationNanos / 1_000_000
                                mutable.update { it.copy(millis = millis) }
                                if (millis >= 59_900) finish(!state.value.locked)
                            }
                            is VideoRecordEvent.Finalize -> {
                                active = null; actions?.cancel(); ghost.sendAction(state.value.chat, "chatActionCancel"); detach()
                                if (cancelled) { file.delete(); return@start }
                                if (event.hasError() && event.error != VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED) { mutable.update { it.copy(phase = VideoNotePhase.Failed, error = "Recording failed. Discard or retry.") }; return@start }
                                encode(file)
                            }
                        }
                    }
            } catch (_: Exception) { cancel("Unable to start video recording.") }
        }
    }
    private fun encode(file: File) {
        mutable.update { it.copy(phase = VideoNotePhase.Encoding) }
        val owner = account
        work = viewModelScope.launch {
            try {
                val out = withContext(Dispatchers.IO) { File(directory(), UUID.randomUUID().toString() + ".mp4") }
                encoded = out
                val result = exportVideoNote(context, file, out)
                if (account != owner) { discard(result); return@launch }
                clip = result; save(result)
                withContext(Dispatchers.IO) { file.delete() }; raw = null
                mutable.update { it.copy(phase = VideoNotePhase.Preview, path = result.path, millis = result.duration * 1000L) }
                if (autoSend) send()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(phase = VideoNotePhase.Failed, error = "Unable to encode a compatible video note. The capture is retained until discarded.") } }
        }
    }
    fun permissionDenied(chat: Long) { mutable.value = VideoNoteState(chat, VideoNotePhase.Failed, error = "Camera and microphone permission were denied. Enable access in app settings, then discard and hold again.") }
    fun retryPreparation() { raw?.let { encode(it) } }
    fun lock() { mutable.update { it.copy(locked = true) } }
    fun release() { if (!state.value.locked) { if (state.value.phase == VideoNotePhase.Starting) cancel() else finish(true) } }
    fun finish(send: Boolean = false) {
        if (state.value.phase !in listOf(VideoNotePhase.Recording, VideoNotePhase.Paused)) return
        autoSend = send; mutable.update { it.copy(phase = VideoNotePhase.Encoding) }; active?.stop()
    }
    fun pause() { if (state.value.phase == VideoNotePhase.Recording) { active?.pause(); mutable.update { it.copy(phase = VideoNotePhase.Paused) }; actions?.cancel(); ghost.sendAction(state.value.chat, "chatActionCancel") }
        else if (state.value.phase == VideoNotePhase.Paused) { active?.resume(); mutable.update { it.copy(phase = VideoNotePhase.Recording) }; actions = viewModelScope.launch { while (isActive) { ghost.sendAction(state.value.chat, "chatActionRecordingVideoNote"); delay(4000) } } } }
    fun switchCamera() { if (state.value.phase in listOf(VideoNotePhase.Starting, VideoNotePhase.Recording, VideoNotePhase.Paused)) mutable.update { it.copy(front = !it.front) } }
    fun send() {
        val note = clip ?: return
        if (state.value.phase !in listOf(VideoNotePhase.Preview, VideoNotePhase.Failed)) return
        val chat = state.value.chat; val replyTo = reply; val owner = account
        mutable.update { it.copy(phase = VideoNotePhase.Sending, error = null) }
        work = viewModelScope.launch {
            try {
                save(note, pending = true)
                val result = chats.sendVideoNote(chat, note, replyTo)
                if (account != owner) return@launch
                when (result.result) {
                    SendResult.Sent -> {
                        sentFiles[result.fileId] = note
                        if (results[result.fileId] in listOf(Delivery.Sent, Delivery.Read)) { sentFiles.remove(result.fileId); discard(note) }
                        clip = null; encoded = null; mutable.value = VideoNoteState(); acceptedEvents.tryEmit(chat to replyTo)
                    }
                    SendResult.Uncertain -> mutable.update { it.copy(phase = VideoNotePhase.Uncertain, error = "Send status unknown. Check the conversation. No automatic retry.") }
                    else -> { save(note); mutable.update { it.copy(phase = VideoNotePhase.Failed, error = "Telegram did not accept this video note. Retry or discard.") } }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(phase = VideoNotePhase.Uncertain, error = "Submission could not be confirmed. Check the conversation before retrying.") } }
        }
    }
    private fun discard(note: VideoNoteClip) {
        val draft = journal()
        viewModelScope.launch(Dispatchers.IO) {
        File(note.path).delete(); note.thumbnail?.let { File(it).delete() }
        runCatching { val current = draft; if (current.isFile && JSONObject(current.readText()).optString("path") == note.path) current.delete() }
    } }
    fun detach() { preview?.let { provider?.unbind(it) }; capture?.let { provider?.unbind(it) }; preview = null; if (active == null) capture = null }
    fun cancel(error: String? = null) {
        val chat = state.value.chat
        generation++; cancelled = true; actions?.cancel(); work?.cancel(); active?.stop(); active = null; detach()
        ghost.sendAction(state.value.chat, "chatActionCancel")
        clip?.let { discard(it) }; clip = null
        raw?.let { file -> viewModelScope.launch(Dispatchers.IO) { file.delete() } }; raw = null
        encoded?.let { file -> viewModelScope.launch(Dispatchers.IO) { file.delete() } }; encoded = null
        mutable.value = if (error == null) VideoNoteState() else VideoNoteState(chat, phase = VideoNotePhase.Failed, error = error)
    }
    fun leave(chat: Long) { if (state.value.chat == chat && state.value.phase in listOf(VideoNotePhase.Starting, VideoNotePhase.Recording, VideoNotePhase.Paused)) cancel(); detach(); playback.stop() }
    override fun onCleared() { active?.stop(); actions?.cancel(); detach(); playback.stop() }
}
