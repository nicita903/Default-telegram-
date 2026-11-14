package com.vector.messenger

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.messenger.feature.*
import org.junit.*
import java.util.concurrent.atomic.AtomicReference

class AccessibleAppearanceUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun systemPreferencesOverrideLiveAndRestoreWithoutChangingSavedIntent() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.targetContext.contentResolver
        val oldMotion = Settings.Global.getString(resolver, Settings.Global.ANIMATOR_DURATION_SCALE)
        val oldHaptics = Settings.System.getString(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED)
        fun shell(command: String) {
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        fun restore(namespace: String, key: String, value: String?) {
            if (value == null) shell("settings delete $namespace $key")
            else { require(value.toFloatOrNull() != null); shell("settings put $namespace $key $value") }
        }
        val saved = AppearanceSettings()
        val effective = AtomicReference<AppearanceSettings>()
        try {
            compose.setContent {
                val current = accessibleAppearance(saved)
                SideEffect { effective.set(current) }
            }
            shell("settings put global animator_duration_scale 0")
            shell("settings put system haptic_feedback_enabled 0")
            compose.waitUntil(5000) { effective.get()?.let { it.reducedMotion && !it.haptics } == true }
            Assert.assertFalse(saved.reducedMotion); Assert.assertTrue(saved.haptics)
            shell("settings put global animator_duration_scale 1")
            shell("settings put system haptic_feedback_enabled 1")
            compose.waitUntil(5000) { effective.get()?.let { !it.reducedMotion && it.haptics } == true }
        } finally {
            restore("global", "animator_duration_scale", oldMotion)
            restore("system", "haptic_feedback_enabled", oldHaptics)
        }
    }
}
