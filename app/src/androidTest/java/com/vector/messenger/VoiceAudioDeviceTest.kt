package com.vector.messenger

import android.Manifest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.*
import com.vector.core.audio.*
import com.vector.core.demo.*
import com.vector.core.media.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.audio.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*

/** Explicitly authorized microphone test: private fixtures, fake send, no Telegram network calls. */
class VoiceAudioDeviceTest {
    @get:Rule val compose = createComposeRule()
    private class Chats : ChatRepository by DemoChatRepository() {
        override val chats = MutableStateFlow(listOf(Chat(-100, "Voice fixture", "", "")))
        override val status = MutableStateFlow(ChatStatus(live = true, session = 1))
        var clip: VoiceClip? = null
        var sent = 0
        override suspend fun sendVoice(chatId: Long, clip: VoiceClip, replyTo: Long?): SendResult { this.clip = clip; sent++; return SendResult.Sent }
    }
    @Test fun realMicrophoneCancelLockFakeSendOpusDecodeAndSinglePlaybackOwner() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val recorder = VoiceRecorder(context)
        val chats = Chats()
        val engine = SharedMediaPlayer(context, scope)
        val store = ViewModelStore()
        lateinit var vm: VoiceViewModel
        withContext(Dispatchers.Main) { vm = VoiceViewModel(recorder, chats, DemoGhostRepository(), engine, scope); store.put("voice", vm) }
        val drafts = File(context.noBackupFilesDir, "voice-recordings")
        val originals = drafts.listFiles()?.map { it.name }?.toSet().orEmpty()
        try {
            compose.setContent { DefaultTheme {
                CompositionLocalProvider(LocalVoiceViewModel provides vm) {
                    androidx.compose.foundation.layout.Column {
                        VoiceRecordingPanel(-100)
                        VoiceMicrophone(-100, null, true)
                    }
                }
            } }
            compose.waitForIdle()
            compose.onNodeWithContentDescription("Record voice message").performTouchInput { down(center) }
            compose.waitUntil(8000) { vm.state.value.phase == RecordingPhase.Recording }
            compose.onNodeWithContentDescription("Record voice message").performTouchInput {
                moveTo(center - Offset(300f, 0f)); up()
            }
            compose.waitUntil(8000) { vm.state.value.phase == RecordingPhase.Idle }
            withContext(Dispatchers.IO) { delay(200) }
            assertEquals(0, chats.sent)
            assertEquals(originals, drafts.listFiles()?.map { it.name }?.toSet().orEmpty())
            compose.onNodeWithContentDescription("Record voice message").performTouchInput { down(center) }
            compose.waitUntil(8000) { vm.state.value.phase == RecordingPhase.Recording }
            compose.onNodeWithContentDescription("Record voice message").performTouchInput {
                moveTo(center - Offset(0f, 300f)); up()
            }
            compose.waitUntil(5000) { vm.state.value.phase == RecordingPhase.Locked }
            delay(1500)
            compose.onNodeWithText("Send voice message").performClick()
            compose.waitUntil(8000) { chats.sent == 1 && vm.state.value.phase == RecordingPhase.Idle }
            val clip = requireNotNull(chats.clip)
            val recorded = File(clip.path)
            assertTrue(recorded.length() > 0)
            recorded.inputStream().use { val header = ByteArray(4); assertEquals(4, it.read(header)); assertEquals("OggS", String(header, Charsets.US_ASCII)) }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(clip.path)
                val format = extractor.getTrackFormat(0)
                assertEquals("audio/opus", format.getString(MediaFormat.KEY_MIME))
                assertEquals(1, format.getInteger(MediaFormat.KEY_CHANNEL_COUNT))
            } finally { extractor.release() }
            val item = MediaItem(-100, 1, TelegramMedia(MediaKind.Voice, MediaFile(1, recorded.length(), complete = true, path = clip.path),
                "Voice fixture", "audio/ogg", duration = clip.duration, waveform = clip.waveform), false)
            withContext(Dispatchers.Main) { engine.loading(item); engine.voice(item, clip.path) }
            withTimeout(8000) { engine.state.first { it.phase == AudioPhase.Playing } }
            withContext(Dispatchers.Main) {
                engine.speed(1.5f); assertEquals(1.5f, engine.state.value.speed)
                engine.seek(.5f); engine.pause()
            }
            withTimeout(5000) { engine.state.first { it.phase == AudioPhase.Paused } }
            withContext(Dispatchers.Main) {
                val video = engine.video(clip.path, 0, false, true)
                assertNull(engine.state.value.item); assertTrue(engine.owns(video))
                engine.releaseVideo(video); assertFalse(engine.owns(video))
            }
            assertEquals(1, chats.sent)
        } finally {
            withContext(Dispatchers.Main) { store.clear(); engine.reset() }
            recorder.cancel(); recorder.discard(chats.clip)
            scope.cancel()
        }
    }
    @Test fun pinnedVoiceRequestAndIncomingWaveformMappingUseOfficialNestedTypes() {
        val waveform = (0 until 100).map { it % 32 }
        val clip = VoiceClip("/private/fixture.ogg", 3, waveform)
        val request = com.vector.messenger.telegram.voiceMessageRequest(-100, clip, 10)
        val content = request.getJSONObject("input_message_content")
        assertEquals("inputMessageVoiceNote", content.getString("@type"))
        val voice = content.getJSONObject("voice_note")
        assertEquals("inputVoiceNote", voice.getString("@type"))
        assertEquals("inputFileLocal", voice.getJSONObject("voice_note").getString("@type"))
        val incoming = JSONObject().put("@type", "messageVoiceNote").put("voice_note", JSONObject()
            .put("duration", 3).put("mime_type", "audio/ogg").put("waveform", voice.getString("waveform"))
            .put("voice", JSONObject().put("id", 7).put("size", 2000).put("local", JSONObject()).put("remote", JSONObject().put("uploaded_size", 1000).put("is_uploading_active", true))))
        val media = com.vector.messenger.telegram.mapTelegramMedia(incoming)!!
        assertEquals(MediaKind.Voice, media.kind); assertEquals(waveform, media.waveform)
        assertEquals(1000L, media.file.uploaded); assertTrue(media.file.uploading)
    }
}
