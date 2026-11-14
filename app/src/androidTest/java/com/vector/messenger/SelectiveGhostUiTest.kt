package com.vector.messenger

import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.vector.core.ThemeMode
import com.vector.core.ghost.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.SelectiveGhostScreen
import org.junit.*

/** Synthetic state only. Never changes a live Telegram account's privacy. */
class SelectiveGhostUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun contactSearchSelectionRemovalAndCombinedStatusControlAreFunctional() {
        val saved = mutableStateOf(GhostSaved())
        val contacts = listOf(GhostContact(10, "Fixture A", "fixture_a"), GhostContact(11, "Fixture B"))
        var applied = false
        compose.setContent { DefaultTheme(ThemeMode.Dark) {
            SelectiveGhostScreen(GhostState(true, true, contacts = contacts, saved = saved.value, serverLoaded = true),
                { saved.value = saved.value.copy(enabled = it) },
                { saved.value = saved.value.copy(contacts = saved.value.contacts + (it to ContactPrivacy())) },
                { saved.value = saved.value.copy(contacts = saved.value.contacts - it) },
                { id, value -> saved.value = saved.value.copy(contacts = saved.value.contacts + (id to value)) }, {}, { applied = true }, {})
        } }
        compose.onNodeWithText("Enable Ghost Mode").performClick().assertIsOn()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasContentDescription("Search Telegram contacts"))
        compose.onNodeWithContentDescription("Search Telegram contacts").performTextInput("fixture_a")
        compose.onNodeWithContentDescription("Select Fixture B for Ghost Mode").assertDoesNotExist()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasContentDescription("Select Fixture A for Ghost Mode"))
        compose.onNodeWithContentDescription("Select Fixture A for Ghost Mode").performClick()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasText("Hide exact Last Seen & Online · Fixture A"))
        compose.onNodeWithText("Hide exact Last Seen & Online · Fixture A").performClick().assertIsOn()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasText("Apply privacy changes"))
        compose.onNodeWithText("Apply privacy changes").performClick()
        compose.runOnIdle { Assert.assertTrue(applied); Assert.assertTrue(saved.value.contacts.getValue(10).status) }
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasContentDescription("Remove Fixture A from Ghost Mode"))
        compose.onNodeWithContentDescription("Remove Fixture A from Ghost Mode").performClick()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasText("No contacts selected"))
        compose.onNodeWithText("No contacts selected").assertIsDisplayed()
    }
    @Test fun conflictRequiresExplicitReviewAndBusyStateDisablesServerChanges() {
        val state = mutableStateOf(GhostState(authenticated = true, initialized = true, conflict = true))
        var kept = false
        compose.setContent { DefaultTheme(ThemeMode.Dark) {
            SelectiveGhostScreen(state.value, {}, {}, {}, { _, _ -> }, {}, {}, { kept = true })
        } }
        compose.onNodeWithText("Apply privacy changes").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasText("Keep current server rules"))
        compose.onNodeWithText("Keep current server rules").performClick()
        compose.runOnIdle { Assert.assertTrue(kept); state.value = state.value.copy(busy = true) }
        compose.onNodeWithText("Keep current server rules").assertIsNotEnabled()
        compose.onNodeWithTag("privacy_scroll").performScrollToNode(hasText("Enable Ghost Mode"))
        compose.onNodeWithText("Enable Ghost Mode").assertIsNotEnabled()
    }
}
