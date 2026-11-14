package com.vector.messenger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import com.vector.messenger.feature.AppearanceSettings
import com.vector.messenger.feature.PreferencesViewModel
import org.junit.*

class AppearanceNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun before() = appearance.before()
    @After fun after() = appearance.after()
    private fun update(change: (AppearanceSettings) -> AppearanceSettings) {
        compose.runOnUiThread { ViewModelProvider(compose.activity)[PreferencesViewModel::class.java].appearance(change) }
        compose.waitForIdle()
    }
    private fun menuSearch() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithTag("search_input").assertIsDisplayed()
    }
    private fun fastRight() {
        compose.onNodeWithTag("search_scope").performTouchInput {
            swipe(center, center + Offset(with(compose.density) { 100.dp.toPx() }, 0f), 120)
        }
    }
    @Test fun densityAndTypographyApplyImmediatelyToTheExistingChatList() {
        val row = compose.onNode(hasText("Alex Morgan") and hasClickAction())
        val text = compose.onNodeWithText("Alex Morgan", useUnmergedTree = true)
        val rowBefore = row.fetchSemanticsNode().boundsInRoot.height
        val textBefore = text.fetchSemanticsNode().boundsInRoot.height
        update { it.copy(compact = false, typeScale = 115, accent = 2) }
        Assert.assertTrue(row.fetchSemanticsNode().boundsInRoot.height > rowBefore)
        Assert.assertTrue(text.fetchSemanticsNode().boundsInRoot.height > textBefore)
        compose.onNodeWithText("New chat").assertIsDisplayed()
        update { it.copy(compact = true, typeScale = 100, accent = 0) }
        Assert.assertEquals(rowBefore, row.fetchSemanticsNode().boundsInRoot.height, .5f)
    }
    @Test fun everyDestinationAndSearchRemainReachableWithGesturesDisabled() {
        update { it.copy(modules = false, pullSearch = false, dismissSearch = false, fastSearch = false, autoLock = false) }
        compose.onNodeWithTag("chat_list").performTouchInput {
            swipe(Offset(center.x, 30f), Offset(center.x, 30f + with(compose.density) { 150.dp.toPx() }), 350)
        }
        compose.onNodeWithTag("search_workspace").assertDoesNotExist()
        menuSearch()
        compose.onNodeWithTag("search_scope").performTouchInput {
            swipe(center, center + Offset(0f, with(compose.density) { 140.dp.toPx() }), 300)
        }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        fastRight()
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("independent query")
        compose.onNodeWithContentDescription("Switch to Music Search").performClick()
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        for (module in listOf("Contacts", "Music", "Downloads", "Ghost Mode", "Settings", "Chats")) {
            compose.onNodeWithContentDescription("Open navigation").performClick()
            compose.onNodeWithText(module).performClick()
            compose.onNodeWithContentDescription("Open navigation").assertIsDisplayed()
        }
    }
    @Test fun dismissalAndScopePreferencesApplyWithoutRestarting() {
        menuSearch()
        update { it.copy(dismissSearch = false, fastSearch = false) }
        fastRight(); compose.onNodeWithText("Chat Search").assertIsDisplayed()
        update { it.copy(fastSearch = true) }
        fastRight(); compose.onNodeWithText("Music Search").assertIsDisplayed()
        update { it.copy(dismissSearch = true) }
        compose.onNodeWithTag("search_scope").performTouchInput {
            swipe(center, center + Offset(0f, with(compose.density) { 140.dp.toPx() }), 300)
        }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun lockFeedbackCanBeDisabledWithoutUnlockingAndAutoLockCanBeDisabledLive() {
        update { it.copy(elastic = false) }
        menuSearch()
        compose.onNodeWithTag("search_input").performTextInput("keep me")
        compose.onNodeWithContentDescription("Search context locked").assertIsDisplayed()
        val workspace = compose.onNodeWithTag("search_workspace")
        val initial = workspace.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        try {
            fastRight(); compose.mainClock.advanceTimeBy(96)
            Assert.assertEquals(initial, workspace.fetchSemanticsNode().boundsInRoot.left, .5f)
            compose.onNodeWithText("Chat Search").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
        update { it.copy(autoLock = false) }
        compose.onNodeWithContentDescription("Search context locked").assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch to Music Search").performClick()
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Switch to Chat Search").performClick()
        compose.onNodeWithTag("search_input").assertTextEquals("keep me")
    }
    @Test fun moduleSensitivityUpdatesLiveAndDisablingCancelsAnActivatedHold() {
        val header = compose.onNodeWithTag("header_gesture_surface")
        val travel = with(compose.density) { 32.dp.toPx() }
        update { it.copy(distance = 60, hold = 120) }
        header.performTouchInput { down(center); moveBy(Offset(travel, 0f)) }
        // Advance event time without a second move; an insufficient distance cannot activate.
        header.performTouchInput { advanceEventTime(450); up() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        update { it.copy(distance = 20) }
        header.performTouchInput { down(center); moveBy(Offset(travel, 0f)) }
        compose.waitUntil(3000) { header.fetchSemanticsNode().config[SemanticsProperties.StateDescription] == "Activated" }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        update { it.copy(modules = false) }
        header.performTouchInput { up() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
    }
}
