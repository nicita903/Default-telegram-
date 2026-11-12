package com.vector.messenger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class FoundationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun accountWindowIsSecureAndReturnsToTheExistingChats() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Account").performClick()
        compose.onNodeWithContentDescription("Close account").assertIsDisplayed()
        compose.runOnIdle {
            val secure = android.view.inspector.WindowInspector.getGlobalWindowViews().any { view ->
                val params = view.layoutParams as? android.view.WindowManager.LayoutParams
                params != null && params.flags and android.view.WindowManager.LayoutParams.FLAG_SECURE != 0
            }
            org.junit.Assert.assertTrue("The account window must use FLAG_SECURE", secure)
        }
        compose.onNodeWithContentDescription("Close account").performClick()
        compose.onNodeWithTag("chat_search").assertIsDisplayed()
    }

    @Test fun searchOpenComposeAndReturn() {
        saveDesignScreenshot(compose.onRoot(), "chats")
        compose.onNodeWithText("Search conversations").performTextInput("Alex")
        compose.onNodeWithText("Alex Morgan").performClick()
        compose.onNodeWithText("Demo message").performTextInput("A local test message")
        compose.onNodeWithContentDescription("Send demo message").performClick()
        compose.onNodeWithText("A local test message").assertIsDisplayed()
        compose.runOnUiThread {
            val input = compose.activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            input.hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
        }
        compose.waitUntil(3000) { androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == false }
        compose.waitForIdle()
        saveDesignScreenshot(compose.onRoot(), "conversation")
        compose.onNodeWithContentDescription("Back to chats").performClick()
        compose.onNodeWithTag("chat_search").assertIsDisplayed()
    }
    @Test fun menuProvidesSettingsMusicAndPrivacy() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light theme").performClick()
        compose.onNodeWithText("Ghost Mode").performClick()
        compose.onNodeWithText("Suppress typing · Alex Morgan").performClick().assertIsOn()
        compose.onNodeWithText("Suppress recording · Alex Morgan").assertIsOff()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Albums").performClick()
        compose.onNodeWithText("No albums yet").assertIsDisplayed()
    }
}
