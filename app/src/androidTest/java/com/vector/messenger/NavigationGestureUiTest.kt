package com.vector.messenger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NavigationGestureUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val header get() = compose.onNodeWithTag("header_gesture_surface")
    private val distance get() = with(compose.density) { 32.dp.toPx() }
    private fun openModule(name: String) {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
        compose.waitUntil(3000) { compose.activity.hasWindowFocus() }
    }
    private fun beginHold(left: Boolean = false, surface: SemanticsNodeInteraction = header) {
        surface.performTouchInput { down(center); moveBy(Offset(if (left) -distance else distance, 0f)) }
        compose.waitUntil(3000) { header.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Activated" }
    }
    private fun systemBack() { androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle(); compose.waitUntil(3000) { compose.activity.hasWindowFocus() } }

    @Test fun rightHoldScalesBeforeReleaseAndOpensOnFirstAttemptRepeatedly() {
        repeat(3) {
            val surface = if (it == 1) compose.onNodeWithTag("module_gesture_surface") else header
            beginHold(surface = surface)
            compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
            surface.performTouchInput { up() }
            compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
            if (it == 0) saveDesignScreenshot(compose.onNode(isDialog()), "module-menu")
            compose.onNodeWithContentDescription("Close navigation").performClick()
            compose.waitForIdle()
        }
    }
    @Test fun leftHoldPopsActualModuleHistory() {
        openModule("Music")
        openModule("Ghost Mode")
        beginHold(left = true)
        compose.onNodeWithText("Suppress typing · Alex Morgan").assertIsDisplayed()
        header.performTouchInput { up() }
        compose.onNodeWithText("Your offline library").assertIsDisplayed()
        beginHold(left = true)
        header.performTouchInput { up() }
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
        // No previous module: stay put, with feedback rather than inventing history.
        beginHold(left = true); header.performTouchInput { up() }
        compose.onNodeWithText("No previous module").assertIsDisplayed()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
    }
    @Test fun earlyReleaseVerticalReversalMultitouchAndSystemCancelNeverNavigate() {
        header.performTouchInput { down(center); moveBy(Offset(distance, 0f)); up() }
        header.performTouchInput { down(center); moveBy(Offset(0f, distance * 2)); up() }
        beginHold()
        header.performTouchInput { moveBy(Offset(-distance, 0f)); up() }
        header.performTouchInput { down(0, center); moveBy(Offset(distance, 0f)); down(1, center + Offset(0f, 10f)); up(1); up(0) }
        beginHold()
        header.performTouchInput { cancel() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
        assertEquals("Idle", header.fetchSemanticsNode().config[SemanticsProperties.StateDescription])
    }
    @Test fun allModulesOpenAndMusicInternalStateSurvivesSwitchingAndBack() {
        openModule("Contacts"); compose.onNodeWithText("Alex Morgan").assertIsDisplayed()
        openModule("Downloads"); compose.onNodeWithText("No downloads").assertIsDisplayed()
        openModule("Ghost Mode"); compose.onNodeWithText("Suppress typing · Alex Morgan").assertIsDisplayed()
        openModule("Settings"); compose.onNodeWithText("Appearance").assertIsDisplayed()
        openModule("Music"); compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithText("No playlists yet").assertIsDisplayed()
        openModule("Chats"); compose.onNodeWithText("Search conversations").performTextInput("Mira")
        openModule("Music"); compose.onNodeWithText("No playlists yet").assertIsDisplayed()
        systemBack() // Internal Music screen before module history.
        compose.onNodeWithText("Your offline library").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Mira Chen").assertIsDisplayed()
        compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
    }
    @Test fun menuDismissesWithBackAndOutsideTapWithoutChangingModule() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        compose.waitUntil(3000) { !compose.activity.hasWindowFocus() }
        systemBack()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNode(isDialog()).performTouchInput { click(Offset(width - 8f, height / 2f)) }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
    }
    @Test fun conversationDraftSurvivesModuleSwitch() {
        compose.onNodeWithText("Alex Morgan").performClick()
        compose.onNodeWithText("Demo message").performTextInput("Keep my draft")
        openModule("Music")
        openModule("Chats")
        compose.onNodeWithText("Keep my draft").assertIsDisplayed()
        systemBack()
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
    }
    @Test fun gesturesCanBeDisabledAndMenuButtonStillWorks() {
        openModule("Settings")
        compose.onNodeWithText("Swipe and hold navigation").performScrollTo().performClick().assertIsOff()
        header.performTouchInput { down(center); moveBy(Offset(distance, 0f)) }
        // Allow a real hold interval; disabled recognition must never activate.
        Thread.sleep(260)
        header.performTouchInput { up() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        openModule("Chats")
        compose.onNodeWithText("Search conversations").assertIsDisplayed()
    }
    @Test fun verticalListScrollAndSearchFieldNeverOpenMenu() {
        compose.onNodeWithTag("chat_list").performTouchInput { swipeUp() }
        compose.onNodeWithText("Search conversations").performTouchInput { down(center); moveBy(Offset(distance, 0f)) }
        Thread.sleep(260)
        compose.onNodeWithText("Search conversations").performTouchInput { up() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithText("Search conversations").performTextInput("Daniel")
        compose.onAllNodesWithText("Daniel").assertCountEquals(2) // Search input plus the matching chat.
    }
    @Test fun messageSwipeRepliesWithoutOpeningModuleMenuAndReactionsToggle() {
        compose.onNodeWithText("Alex Morgan").performClick()
        val message = compose.onNodeWithText("Hi! This conversation uses sample messages.")
        message.performTouchInput { swipeRight() }
        compose.onNodeWithText("Reply to Alex Morgan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithText("Demo message").performTextInput("A reply")
        compose.onNodeWithContentDescription("Send demo message").performClick()
        compose.onNodeWithTag("message_list").performScrollToNode(hasText("A reply"))
        saveDesignScreenshot(compose.onRoot(), "reply")
        compose.onNodeWithText("Reply").assertIsDisplayed()
        compose.onNodeWithText("A reply").performTouchInput { longClick() }
        compose.onNodeWithText("👍").performClick()
        compose.onNodeWithText("👍  1").assertIsDisplayed().performClick()
        compose.onNodeWithText("👍  1").assertDoesNotExist()
    }
    @Test fun verticalScrollingWinsAndRestoresItsPositionAcrossModules() {
        openModule("Ghost Mode")
        compose.onNodeWithTag("privacy_scroll").performTouchInput { swipeUp() }
        compose.onNodeWithText("Hide last seen · Daniel").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        openModule("Music")
        openModule("Ghost Mode")
        compose.onNodeWithText("Hide last seen · Daniel").assertIsDisplayed()
    }
}
