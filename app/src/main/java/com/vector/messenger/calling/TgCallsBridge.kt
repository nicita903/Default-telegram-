package com.vector.messenger.calling

import android.content.Context
import androidx.annotation.Keep
import org.json.JSONObject
import kotlinx.coroutines.channels.Channel

sealed interface NativeCallEvent {
    data class State(val handle: Long, val value: Int) : NativeCallEvent
    class Signal(val handle: Long, val bytes: ByteArray) : NativeCallEvent
}
/** No JNI handles are pointers: native owns a guarded registry and ignores released IDs. */
@Keep
class TgCallsBridge(context: Context) {
    val events = Channel<NativeCallEvent>(Channel.UNLIMITED)
    val capabilities: JSONObject
    init {
        System.loadLibrary("default_calls")
        org.webrtc.ContextUtils.initialize(context.applicationContext)
        capabilities = JSONObject(nativeCapabilities())
        check(capabilities.getJSONArray("versions").length() > 0 && capabilities.getInt("layer") > 0)
    }
    fun create(version: String, key: ByteArray, options: JSONObject): Long = nativeCreate(version, key, options.toString(), this).also { check(it != 0L) }
    fun mute(handle: Long, value: Boolean) = nativeMute(handle, value)
    fun signal(handle: Long, data: ByteArray) = nativeSignal(handle, data)
    fun stop(handle: Long) = nativeStop(handle)
    @Keep fun onNativeState(handle: Long, state: Int) { events.trySend(NativeCallEvent.State(handle, state)) }
    @Keep fun onNativeSignal(handle: Long, value: ByteArray) { events.trySend(NativeCallEvent.Signal(handle, value)) }
    private external fun nativeCapabilities(): String
    private external fun nativeCreate(version: String, key: ByteArray, options: String, listener: TgCallsBridge): Long
    private external fun nativeMute(handle: Long, muted: Boolean)
    private external fun nativeSignal(handle: Long, data: ByteArray)
    private external fun nativeStop(handle: Long)
}
