package com.vector.messenger.calling

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log

internal enum class CallEvent { Created, EngineInitialized, Connecting, Established, Reconnecting, Recovered,
    NetworkChanged, SignalingDeferred, Terminating, NativeStopRequested, NativeReleased, ServiceStopped, CleanupFailure, RouteChanged, IncomingDetected, NativeCallNotification, TelecomRegistered, IncomingPosted, AnswerRequested, PlatformFailure }
internal fun callDiagnostic(context: Context, event: CallEvent) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) Log.d("DefaultVoiceCall", "t=${android.os.SystemClock.elapsedRealtime()} ${event.name}")
}
