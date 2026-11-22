package com.vector.core.audio

import com.vector.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

interface VoiceCapture {
    suspend fun start(interrupted: (String) -> Unit)
    suspend fun meter(): Pair<Long, Int>
    suspend fun finish(amplitudes: List<Int>): VoiceClip
    suspend fun cancel()
    suspend fun handoff(clip: VoiceClip): VoiceClip
    suspend fun discard(clip: VoiceClip?)
}
enum class RecordingPhase { Idle, Starting, Recording, Locked, Finishing, Sending, Failed, Uncertain }
data class RecordingState(val chatId: Long? = null, val phase: RecordingPhase = RecordingPhase.Idle,
    val duration: Long = 0, val amplitudes: List<Int> = emptyList(), val error: String? = null, val retryAvailable: Boolean = false,
    val permissionRequired: Boolean = false)
data class VoiceAccepted(val chatId: Long, val replyTo: Long?)

/** Recording ownership and send lifecycle, independent of Android for deterministic testing. */
class VoiceRecordingController(private val recorder: VoiceCapture, private val chats: ChatRepository,
    private val ghost: GhostRepository, private val stopPlayback: () -> Unit,
    private val scope: CoroutineScope, private val cleanupScope: CoroutineScope) {
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    private val acceptedEvents = MutableSharedFlow<VoiceAccepted>(extraBufferCapacity = 1)
    val accepted = acceptedEvents.asSharedFlow()
    private var job: Job? = null
    private var cleanup: Job? = null
    private var clip: VoiceClip? = null
    private var reply: Long? = null
    private var epoch = 0L
    private val measured = ArrayList<Int>(7500)
    private var lockRequested = false
    init { scope.launch { chats.status.map { it.session }.distinctUntilChanged().collect { cancel() } } }
    fun permissionDenied() { mutable.value = RecordingState(error = "Microphone permission is required. Allow it, then hold the microphone again. If Android no longer asks, use Microphone settings.", permissionRequired = true) }
    fun start(chatId: Long, replyTo: Long?, locked: Boolean = false) {
        if (!chats.status.value.live || chats.chats.value.find { it.id == chatId }?.canSend != true || mutable.value.phase != RecordingPhase.Idle) return
        val generation = ++epoch; reply = replyTo; stopPlayback(); measured.clear(); lockRequested = locked
        val precedingCleanup = cleanup
        mutable.value = RecordingState(chatId, RecordingPhase.Starting)
        job = scope.launch {
            try {
                precedingCleanup?.join(); ensureActive()
                recorder.start { reason -> if (epoch == generation) cancel(reason) }
                ensureActive(); if (epoch != generation) return@launch
                mutable.update { it.copy(phase = if (lockRequested) RecordingPhase.Locked else RecordingPhase.Recording) }
                launch { while (isActive) { ghost.sendAction(chatId, "chatActionRecordingVoiceNote"); delay(4000) } }
                while (isActive) {
                    val (elapsed, amplitude) = recorder.meter()
                    if (measured.size < 7500) measured += amplitude
                    mutable.update { it.copy(duration = elapsed, amplitudes = measured.takeLast(48)) }
                    delay(80)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { cancel("Unable to use the microphone. Check permission, calls and Android microphone privacy controls.") }
        }
    }
    fun lock() { lockRequested = true; if (mutable.value.phase == RecordingPhase.Recording) mutable.update { it.copy(phase = RecordingPhase.Locked) } }
    private fun stopJobs() {
        job?.cancel(); job = null
        mutable.value.chatId?.let { ghost.sendAction(it, "chatActionCancel") }
    }
    fun send() {
        if (mutable.value.phase == RecordingPhase.Starting) { cancel("Hold until recording begins before releasing."); return }
        if (mutable.value.phase !in listOf(RecordingPhase.Recording, RecordingPhase.Locked, RecordingPhase.Failed)) return
        if (mutable.value.phase == RecordingPhase.Failed && clip == null) return
        val chatId = mutable.value.chatId ?: return
        val amplitudes = measured.toList()
        val session = chats.status.value.session
        stopJobs(); mutable.update { it.copy(phase = RecordingPhase.Finishing, error = null) }
        val generation = ++epoch
        job = scope.launch {
            var issued = false
            var outgoing: VoiceClip? = clip
            try {
                if (outgoing == null) withContext(NonCancellable) { outgoing = recorder.finish(amplitudes) }
                ensureActive()
                withContext(NonCancellable) { outgoing = recorder.handoff(requireNotNull(outgoing)) }
                clip = outgoing
                ensureActive()
                if (chats.status.value.session != session) return@launch
                mutable.update { it.copy(phase = RecordingPhase.Sending) }
                issued = true
                val result = chats.sendVoice(chatId, requireNotNull(outgoing), reply)
                ensureActive()
                if (generation != epoch || chats.status.value.session != session) return@launch
                when (result) {
                    SendResult.Sent -> { acceptedEvents.tryEmit(VoiceAccepted(chatId, reply)); clip = null; mutable.value = RecordingState() }
                    SendResult.Uncertain -> { clip = null; mutable.update { it.copy(phase = RecordingPhase.Uncertain, error = "Telegram's response was not confirmed. Check the conversation before recording again. No automatic resend.") } }
                    else -> mutable.update { it.copy(phase = RecordingPhase.Failed, error = "Voice message was not accepted. Retry or cancel.", retryAvailable = true) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (generation == epoch) mutable.update { it.copy(phase = RecordingPhase.Failed, error = "Recording could not be finalized. Cancel or try again.") } }
            finally { if (!issued) withContext(NonCancellable) { recorder.discard(outgoing); recorder.cancel() } }
        }
    }
    fun cancel(error: String? = null) {
        val previous = mutable.value.phase
        ++epoch; stopJobs()
        val discarded = clip; clip = null
        mutable.value = RecordingState(error = error)
        val previousCleanup = cleanup
        cleanup = cleanupScope.launch {
            previousCleanup?.join()
            withContext(NonCancellable) { recorder.cancel(); if (previous != RecordingPhase.Sending && previous != RecordingPhase.Uncertain) recorder.discard(discarded) }
        }
    }
    fun leave(chatId: Long) { if (mutable.value.chatId == chatId && mutable.value.phase !in listOf(RecordingPhase.Sending, RecordingPhase.Uncertain)) cancel() }
    fun close() = cancel()
}
