package com.vector.messenger.feature.audio

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.*
import com.vector.core.*
import com.vector.core.audio.VoiceRecordingController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope

val LocalVoiceViewModel = staticCompositionLocalOf<VoiceViewModel?> { null }

@HiltViewModel
class VoiceViewModel @Inject constructor(recorder: VoiceRecorder, chats: ChatRepository,
    ghost: GhostRepository, engine: SharedMediaPlayer, cleanupScope: CoroutineScope) : ViewModel() {
    private val controller = VoiceRecordingController(recorder, chats, ghost, { engine.stop() }, viewModelScope, cleanupScope)
    val state = controller.state
    val accepted = controller.accepted
    fun start(chatId: Long, reply: Long?, locked: Boolean = false) = controller.start(chatId, reply, locked)
    fun send() = controller.send()
    fun lock() = controller.lock()
    fun cancel(error: String? = null) = controller.cancel(error)
    fun leave(chatId: Long) = controller.leave(chatId)
    fun permissionDenied() = controller.permissionDenied()
    override fun onCleared() { controller.close() }
}
