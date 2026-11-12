package com.vector.messenger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

class FoundationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun searchOpenComposeAndReturn() {
        compose.onNodeWithText("Search conversations").performTextInput("Alex")
        compose.onNodeWithText("Alex Morgan").performClick()
        compose.onNodeWithText("Demo message").performTextInput("A local test message")
        compose.onNodeWithContentDescription("Send demo message").performClick()
        compose.onNodeWithText("A local test message").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to chats").performClick()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
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
