package com.vector.messenger

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.vector.core.profile.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.AccountProfileContent
import org.junit.*

/** Synthetic state rendering only. Does not authenticate with Telegram. */
class TelegramProfileUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun downloadedPhotoDecodesAndRemovalRestoresTheFallback() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val file = java.io.File.createTempFile("profile_fixture", ".png", context.cacheDir)
        try {
            val bitmap = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            val profile = TelegramProfile(99, "Fixture", "Account", null, null, ProfilePhoto(7, file.path))
            val state = mutableStateOf(ProfileState(authenticated = true, profile = profile, connection = ProfileConnection.Ready))
            compose.setContent { DefaultTheme { AccountProfileContent(state.value, {}) } }
            compose.waitUntil(5000) { compose.onAllNodesWithContentDescription("Telegram profile picture").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Telegram profile picture").assertIsDisplayed()
            compose.runOnIdle { state.value = state.value.copy(profile = profile.copy(photo = null)) }
            compose.onNodeWithContentDescription("Telegram profile picture").assertDoesNotExist()
            compose.onNodeWithText("Fixture Account").assertIsDisplayed()
        } finally { file.delete() }
    }
    @Test fun missingFieldsLoadingOfflineAndLogoutRenderWithoutDemoIdentity() {
        val state = mutableStateOf(ProfileState(authenticated = true, loading = true, connection = ProfileConnection.Ready))
        var refreshed = false
        compose.setContent { DefaultTheme { AccountProfileContent(state.value, { refreshed = true }) } }
        compose.onNodeWithText("Loading Telegram profile…").assertIsDisplayed()
        compose.runOnIdle { state.value = ProfileState(authenticated = true,
            profile = TelegramProfile(99, "Fixture", "", null, null, null), connection = ProfileConnection.WaitingForNetwork) }
        compose.onNodeWithText("No username").assertIsDisplayed()
        compose.onNodeWithText("99").assertIsDisplayed()
        compose.onNodeWithText("Not available").assertIsDisplayed()
        compose.onNodeWithText("Network unavailable · cached information may be shown").assertIsDisplayed()
        compose.onNodeWithText("Refresh profile").performClick()
        compose.runOnIdle { Assert.assertTrue(refreshed); state.value = ProfileState() }
        compose.onNodeWithText("No Telegram account signed in").assertIsDisplayed()
        compose.onNodeWithText("Fixture").assertDoesNotExist()
        compose.onNodeWithText("99").assertDoesNotExist()
    }
    @Test fun userUpdatesReplaceTheDisplayedProfileFields() {
        val profile = TelegramProfile(99, "Fixture", "Account", "fixture_handle", "12025550123", null)
        val state = mutableStateOf(ProfileState(authenticated = true, profile = profile, connection = ProfileConnection.Ready))
        compose.setContent { DefaultTheme { AccountProfileContent(state.value, {}) } }
        compose.onNodeWithText("Fixture Account").assertIsDisplayed()
        compose.onNodeWithText("+12025550123").assertIsDisplayed()
        compose.runOnIdle { state.value = state.value.copy(profile = profile.copy(firstName = "Updated", username = null)) }
        compose.onNodeWithText("Updated Account").assertIsDisplayed()
        compose.onAllNodesWithText("@fixture_handle").assertCountEquals(0)
    }
}
