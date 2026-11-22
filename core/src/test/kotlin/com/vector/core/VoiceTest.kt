package com.vector.core

import com.vector.core.audio.*
import com.vector.core.demo.*
import com.vector.core.ghost.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceTest {
    private class Capture : VoiceCapture {
        var active = false; var starts = 0; var finishes = 0
        var startGate: CompletableDeferred<Unit>? = null
        var finishGate: CompletableDeferred<Unit>? = null
        val discarded = mutableListOf<String>()
        override suspend fun start(interrupted: (String) -> Unit) { starts++; startGate?.await(); active = true }
        override suspend fun meter() = 1200L to 10000
        override suspend fun finish(amplitudes: List<Int>): VoiceClip { finishes++; finishGate?.await(); active = false; return VoiceClip("/fixture/draft.ogg", 2, voiceWaveform(amplitudes)) }
        override suspend fun cancel() { active = false }
        override suspend fun handoff(clip: VoiceClip) = clip.copy(path = "/fixture/native.ogg")
        override suspend fun discard(clip: VoiceClip?) { clip?.let { discarded += it.path } }
    }
    private class Chats : ChatRepository by DemoChatRepository() {
        override val chats = MutableStateFlow(listOf(Chat(-100, "Fixture", "", "", canSend = true)))
        override val status = MutableStateFlow(ChatStatus(live = true, session = 1))
        var sent = 0; var result: SendResult = SendResult.Sent
        var gate: CompletableDeferred<Unit>? = null
        var reply: Long? = null
        override suspend fun sendVoice(chatId: Long, clip: VoiceClip, replyTo: Long?): SendResult { sent++; reply = replyTo; gate?.await(); return result }
    }
    private class Ghost : GhostRepository by DemoGhostRepository() {
        var saved = GhostSaved()
        val actions = mutableListOf<String>()
        override fun sendAction(chatId: Long, action: String) { if (saved.allowsAction(42, action)) actions += action }
    }
    @Test fun waveformUsesKnownFiveBitPackingAndMeasuredSilence() {
        assertArrayEquals(byteArrayOf(-1, 127), packWaveform(listOf(31, 31, 31)))
        val samples = (0 until 100).map { it % 32 }
        assertEquals(samples, unpackWaveform(packWaveform(samples)))
        assertTrue(voiceWaveform(List(200) { 0 }).all { it == 0 })
        assertEquals(listOf(0, 31, 15), voiceWaveform(listOf(0, 32767, 16384)))
        assertTrue(voiceWaveform(List(7500) { it }).size <= 100)
    }
    @Test fun gestureCancelLockAndReleaseAreDeterministicAndOneShot() {
        val cancelled = VoiceGesture(72f, 64f)
        assertEquals(VoiceGestureAction.Cancel, cancelled.move(-80f, 0f)); assertEquals(VoiceGestureAction.None, cancelled.release())
        val locked = VoiceGesture(72f, 64f)
        assertEquals(VoiceGestureAction.Lock, locked.move(0f, -70f)); assertEquals(VoiceGestureAction.None, locked.release())
        val normal = VoiceGesture(72f, 64f)
        assertEquals(VoiceGestureAction.Send, normal.release()); assertEquals(VoiceGestureAction.None, normal.release())
    }
    @Test fun cancellationNeverSubmitsAndStopsRecording() = runTest {
        val capture = Capture(); val chats = Chats(); val ghost = Ghost()
        val controller = VoiceRecordingController(capture, chats, ghost, {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); assertTrue(capture.active)
        controller.cancel(); runCurrent(); assertFalse(capture.active); assertEquals(0, chats.sent)
        assertEquals(RecordingPhase.Idle, controller.state.value.phase); assertTrue("chatActionCancel" in ghost.actions)
    }
    @Test fun lockedRecordingSendsOnceWithReplyAndOnlyClearsAfterNativeAcceptance() = runTest {
        val capture = Capture(); val chats = Chats(); val ghost = Ghost()
        val controller = VoiceRecordingController(capture, chats, ghost, {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, 77, true); runCurrent(); assertEquals(RecordingPhase.Locked, controller.state.value.phase)
        chats.gate = CompletableDeferred(); controller.send(); controller.send(); runCurrent()
        assertEquals(1, chats.sent); assertEquals(77L, chats.reply); assertEquals(RecordingPhase.Sending, controller.state.value.phase)
        chats.gate!!.complete(Unit); runCurrent(); assertEquals(RecordingPhase.Idle, controller.state.value.phase)
        assertTrue(capture.discarded.isEmpty()) // Accepted file is handed to TDLib, not deleted while uploading.
    }
    @Test fun releaseDuringRecorderPreparationCancelsInsteadOfLeavingAnOpenMicrophone() = runTest {
        val capture = Capture(); val chats = Chats(); capture.startGate = CompletableDeferred()
        val controller = VoiceRecordingController(capture, chats, Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); assertEquals(RecordingPhase.Starting, controller.state.value.phase)
        controller.send(); capture.startGate!!.complete(Unit); runCurrent()
        assertFalse(capture.active); assertEquals(0, chats.sent); assertEquals(RecordingPhase.Idle, controller.state.value.phase)
    }
    @Test fun lockDuringPreparationIsAppliedWhenRecorderBecomesReady() = runTest {
        val capture = Capture(); capture.startGate = CompletableDeferred()
        val controller = VoiceRecordingController(capture, Chats(), Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); controller.lock(); capture.startGate!!.complete(Unit); runCurrent()
        assertEquals(RecordingPhase.Locked, controller.state.value.phase)
        controller.cancel(); runCurrent()
    }
    @Test fun failedSubmissionRetainsOneClipForRetryAndCancelDiscardsIt() = runTest {
        val capture = Capture(); val chats = Chats(); chats.result = SendResult.Failed
        val controller = VoiceRecordingController(capture, chats, Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); controller.send(); runCurrent()
        assertEquals(RecordingPhase.Failed, controller.state.value.phase); assertTrue(controller.state.value.retryAvailable)
        controller.send(); runCurrent(); assertEquals(1, capture.finishes); assertEquals(2, chats.sent)
        controller.cancel(); runCurrent(); assertTrue("/fixture/native.ogg" in capture.discarded)
    }
    @Test fun uncertainOutcomeNeverAutomaticallyRetriesOrDeletesPotentiallyUploadingSource() = runTest {
        val capture = Capture(); val chats = Chats(); chats.result = SendResult.Uncertain
        val controller = VoiceRecordingController(capture, chats, Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); controller.send(); runCurrent()
        assertEquals(RecordingPhase.Uncertain, controller.state.value.phase)
        controller.send(); runCurrent(); assertEquals(1, chats.sent)
        controller.cancel(); runCurrent(); assertTrue(capture.discarded.isEmpty())
    }
    @Test fun logoutDuringFinalizeDiscardsTheLateClipWithoutSending() = runTest {
        val capture = Capture(); val chats = Chats(); capture.finishGate = CompletableDeferred()
        val controller = VoiceRecordingController(capture, chats, Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); controller.send(); runCurrent()
        chats.status.value = ChatStatus(live = false, session = 2); runCurrent()
        capture.finishGate!!.complete(Unit); runCurrent()
        assertEquals(0, chats.sent); assertFalse(capture.active)
        assertEquals(1, capture.discarded.size)
        assertTrue(capture.discarded.single() in listOf("/fixture/draft.ogg", "/fixture/native.ogg"))
    }
    @Test fun ghostSuppressesRecordingActivityWithoutSuppressingActualVoiceSubmission() = runTest {
        val capture = Capture(); val chats = Chats(); val ghost = Ghost()
        ghost.saved = GhostSaved(true, mapOf(42L to ContactPrivacy(recording = true)))
        val controller = VoiceRecordingController(capture, chats, ghost, {}, backgroundScope, backgroundScope); runCurrent()
        controller.start(-100, null); runCurrent(); advanceTimeBy(4500); runCurrent()
        assertFalse("chatActionRecordingVoiceNote" in ghost.actions)
        controller.send(); runCurrent(); assertEquals(1, chats.sent); assertTrue("chatActionCancel" in ghost.actions)
    }
    @Test fun leavingLockedRecordingAndPermissionFailureKeepTextMessagingAvailable() = runTest {
        val capture = Capture(); val chats = Chats()
        val controller = VoiceRecordingController(capture, chats, Ghost(), {}, backgroundScope, backgroundScope); runCurrent()
        controller.permissionDenied(); assertEquals(RecordingPhase.Idle, controller.state.value.phase)
        controller.start(-100, null, true); runCurrent(); controller.leave(-100); runCurrent()
        assertFalse(capture.active); assertEquals(0, chats.sent)
    }
}
