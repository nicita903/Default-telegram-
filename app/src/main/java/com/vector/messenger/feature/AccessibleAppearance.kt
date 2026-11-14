package com.vector.messenger.feature

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Platform restrictions are effective overrides, never written over the user's preferences. */
@Composable
fun accessibleAppearance(settings: AppearanceSettings): AppearanceSettings {
    val resolver = LocalContext.current.contentResolver
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var animations by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    var haptics by remember { mutableStateOf(Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0) }
    DisposableEffect(resolver, lifecycle) {
        fun refresh() {
            animations = ValueAnimator.areAnimatorsEnabled()
            haptics = Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0
        }
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = refresh()
        }
        var registered = false
        fun start() {
            if (!registered) {
                resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
                resolver.registerContentObserver(Settings.System.getUriFor(Settings.System.HAPTIC_FEEDBACK_ENABLED), false, observer)
                registered = true
            }
            refresh()
        }
        fun stop() { if (registered) { resolver.unregisterContentObserver(observer); registered = false } }
        val listener = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) start()
            else if (event == Lifecycle.Event.ON_STOP) stop()
        }
        lifecycle.addObserver(listener)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) start()
        onDispose { lifecycle.removeObserver(listener); stop() }
    }
    return settings.respectingSystemMotion(animations).copy(haptics = settings.haptics && haptics)
}
