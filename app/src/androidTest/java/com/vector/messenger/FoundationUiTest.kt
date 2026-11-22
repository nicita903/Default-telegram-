package com.vector.messenger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class FoundationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun resetAppearance() = appearance.before()
    @After fun restoreAppearance() = appearance.after()

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
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }

    @Test fun searchOpenComposeAndReturn() {
        saveDesignScreenshot(compose.onRoot(), "chats")
        openChatSearch(compose)
        compose.onNodeWithText("Search conversations").performTextInput("Alex")
        compose.onNodeWithText("Alex Morgan").performClick()
        compose.onNodeWithText("Demo message").performTextInput("A local test message")
        compose.onNodeWithContentDescription("Send demo message").performClick()
        compose.onNodeWithText("A local test message").assertIsDisplayed()
        awaitConversationKeyboardClosed(compose)
        saveDesignScreenshot(compose.onRoot(), "conversation")
        compose.onNodeWithContentDescription("Back to chats").performClick()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun menuProvidesSettingsMusicAndPrivacy() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Light theme").performClick()
        compose.onNodeWithText("Ghost Mode").performScrollTo().performClick()
        compose.onNodeWithText("Suppress typing · Alex Morgan").performClick().assertIsOn()
        compose.onNodeWithText("Suppress recording · Alex Morgan").assertIsOff()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Albums").performClick()
        // Navigation must also work when the device has a populated music library.
        compose.onNodeWithTag("music_section:Albums").assertIsDisplayed()
    }
}
