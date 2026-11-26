package com.vector.messenger.telegram

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.vector.core.messaging.SessionEvent
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.collectLatest

/** Debug-only, monotonic, fixed-stage telemetry. Never accepts chat IDs, users, payloads or errors. */
internal object SessionDiagnostics {
    private var installed = false
    private var foregroundAt = -1L
    private var backgroundAt = -1L
    private var visits = 0L
    private var freshVisit = -1L
    private var created = 0
    @Synchronized fun mark(context: Context, event: SessionEvent, waitMs: Long = 0) {
        when (event) {
            SessionEvent.AppForegrounded -> com.vector.messenger.notifications.PushDiagnostics.lifecycle(true)
            SessionEvent.AppBackgrounded -> com.vector.messenger.notifications.PushDiagnostics.lifecycle(false)
            SessionEvent.ClientCreated -> com.vector.messenger.notifications.PushDiagnostics.event("TDLIB_CLIENT_CREATED")
            SessionEvent.ClientClosed -> com.vector.messenger.notifications.PushDiagnostics.event("TDLIB_CLIENT_CLOSED")
            else -> Unit
        }
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val now = SystemClock.elapsedRealtime()
        if (event == SessionEvent.ClientCreated) created++
        if (event == SessionEvent.AppForegrounded) { foregroundAt = now; visits++ }
        if (event == SessionEvent.AppBackgrounded) { backgroundAt = now; visits++ }
        if (event == SessionEvent.FreshMessagesApplied) { if (freshVisit == visits) return; freshVisit = visits }
        Log.d("DefaultSession", "t=$now pid=${Process.myPid()} event=${event.name} creates=$created fgMs=${if (foregroundAt < 0) -1 else now - foregroundAt} bgMs=${if (backgroundAt < 0) -1 else now - backgroundAt} waitMs=$waitMs")
    }
    fun install(app: Application) {
        if (installed) return; installed = true
        mark(app, SessionEvent.ProcessStarted)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            var started = 0
            var foreground = false
            override fun onActivityCreated(activity: Activity, state: Bundle?) { mark(app, SessionEvent.ActivityCreated) }
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0 && !foreground) { foreground = true; mark(app, SessionEvent.AppForegrounded) }
            }
            override fun onActivityStopped(activity: Activity) {
                mark(app, SessionEvent.ActivityStopped)
                started = (started - 1).coerceAtLeast(0)
                if (started == 0 && !activity.isChangingConfigurations) { foreground = false; mark(app, SessionEvent.AppBackgrounded) }
            }
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

/** A frame-bound UI readiness marker, not a claim that Telegram has synchronized. */
@Composable
internal fun SessionUiTimeline(populated: Boolean, conversation: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(populated, lifecycle, conversation) {
        lifecycle.currentStateFlow.collectLatest { state ->
            if (populated && state.isAtLeast(Lifecycle.State.RESUMED)) {
                withFrameNanos { }
                SessionDiagnostics.mark(context, if (conversation) SessionEvent.ConversationUiVisible else SessionEvent.ChatUiVisible)
            }
        }
    }
}
