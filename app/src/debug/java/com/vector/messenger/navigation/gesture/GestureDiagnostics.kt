package com.vector.messenger.navigation.gesture

import android.util.Log

/** Opt in with adb shell setprop log.tag.DefaultGesture DEBUG, or from a debugger/test. */
object GestureDiagnostics {
    @Volatile var enabled = false
    var observer: ((GestureDiagnosticEvent) -> Unit)? = null
    fun record(state: VectorGestureState, direction: VectorGestureDirection?, thresholdDp: Float,
               holdMillis: Long, reason: GestureCancelReason = GestureCancelReason.None,
               action: GestureNavigationAction = GestureNavigationAction.None) {
        if (!enabled && !Log.isLoggable("DefaultGesture", Log.DEBUG)) return
        val event = GestureDiagnosticEvent(state, direction, thresholdDp, holdMillis, reason, action)
        observer?.invoke(event)
        Log.d("DefaultGesture", event.toString())
    }
}
