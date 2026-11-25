package com.vector.messenger.calling

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

internal enum class CallEvent { Created, EngineInitialized, Connecting, Established, Reconnecting, Recovered,
    NetworkChanged, SignalingDeferred, Terminating, NativeStopRequested, NativeReleased, ServiceStopped, CleanupFailure, RouteChanged, IncomingDetected, NativeCallNotification, TelecomRegistered, IncomingPosted, AnswerRequested, PlatformFailure }
internal fun callDiagnostic(context: Context, event: CallEvent) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("DefaultVoiceCall", "t=${android.os.SystemClock.elapsedRealtime()} ${event.name}")
}

/** Platform-only diagnostics. Never attach a Throwable: its message may include caller identity. */
internal fun telecomDiagnostic(context: Context, operation: String, registered: Boolean, outgoing: Boolean?,
    active: Boolean, cancelled: Boolean = false, error: Throwable? = null, code: Int? = null, detail: String = "") {
    val debug = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    if (!debug && error == null && code == null) return
    val safeMessage = when {
        error?.message?.contains("CallStyle notifications must") == true -> "CallStyle requires a foreground service, user initiated job or fullScreenIntent"
        error is SecurityException -> "Android rejected a required permission or call operation"
        error is kotlinx.coroutines.TimeoutCancellationException -> "Android call setup timed out"
        error is kotlinx.coroutines.CancellationException -> "Call scope cancelled"
        error is androidx.core.telecom.CallException -> "Android Telecom rejected the operation"
        error != null -> "Message omitted to protect call identity"
        else -> "none"
    }
    val frames = error?.stackTrace?.take(6)?.joinToString(" <- ") { "${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
    val line = "op=$operation sdk=${android.os.Build.VERSION.SDK_INT} coreTelecom=1.0.0 registered=$registered " +
        "direction=${outgoing?.let { if (it) "outgoing" else "incoming" } ?: "none"} active=$active cancelled=$cancelled " +
        "exception=${error?.javaClass?.name ?: "none"} code=${code ?: (error as? androidx.core.telecom.CallException)?.code ?: "none"} " +
        "message=$safeMessage $detail frames=$frames"
    if (error != null || code != null) Log.e("DefaultTelecom", line) else Log.d("DefaultTelecom", line)
}
