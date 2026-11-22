package com.vector.messenger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.vector.messenger.feature.AppearanceSettings
import com.vector.messenger.feature.PreferencesViewModel

/** Persisted user appearance is restored after every test, including assertion failures. */
class TerminalTestAppearance(private val compose: AndroidComposeTestRule<*, MainActivity>) {
    private lateinit var previous: AppearanceSettings
    private lateinit var preferences: PreferencesViewModel
    fun before() {
        lateinit var authentication: com.vector.messenger.feature.AuthenticationViewModel
        compose.runOnUiThread {
            authentication = androidx.lifecycle.ViewModelProvider(compose.activity)[com.vector.messenger.feature.AuthenticationViewModel::class.java]
        }
        compose.waitUntil(10000) { authentication.state.value.step != com.vector.core.auth.AuthStep.Starting }
        org.junit.Assume.assumeFalse("Demo UI regressions must not send through a real account", authentication.state.value.step == com.vector.core.auth.AuthStep.Ready)
        compose.runOnUiThread {
            compose.activity.window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            preferences = ViewModelProvider(compose.activity)[PreferencesViewModel::class.java]
            previous = preferences.appearance.value
            preferences.appearance { AppearanceSettings() }
        }
        compose.waitForIdle()
    }
    fun after() { if (::preferences.isInitialized) compose.runOnUiThread {
        preferences.appearance { previous }
        compose.activity.window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } }
}

fun openChatSearch(compose: AndroidComposeTestRule<*, MainActivity>) {
    val distance = with(compose.density) { 130.dp.toPx() }
    compose.onNodeWithTag("chat_list").performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + distance), 400) }
    compose.onNodeWithTag("search_input").assertIsDisplayed()
}
