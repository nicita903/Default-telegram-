package com.vector.messenger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*

class SearchNavigationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun before() = appearance.before()
    @After fun after() = appearance.after()
    private val distance get() = with(compose.density) { 130.dp.toPx() }
    private fun dismissHeader() { compose.onNodeWithTag("search_scope").performTouchInput { swipe(center, center + Offset(0f, distance), 350) } }
    private fun back() { InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK); compose.waitForIdle() }
    private fun imeVisible(): Boolean { var visible = false; compose.runOnUiThread { visible = ViewCompat.getRootWindowInsets(compose.activity.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime()) == true }; return visible }
    @Test fun resultsPullRevealsChatsAndClosesOnlyAfterRelease() {
        openChatSearch(compose)
        val results = compose.onNodeWithTag("search_results")
        val before = compose.onNodeWithTag("search_scope").fetchSemanticsNode().boundsInRoot.top
        results.performTouchInput { down(Offset(center.x, 30f)); moveBy(Offset(0f, distance), 300) }
        Assert.assertTrue(compose.onNodeWithTag("search_scope").fetchSemanticsNode().boundsInRoot.top > before)
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "search-dismiss-reveal")
        results.performTouchInput { up() }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        compose.onNodeWithTag("search_workspace").assertDoesNotExist()
    }
    @Test fun bothScopesDismissWithoutClearingTheirIndependentQueries() {
        openChatSearch(compose)
        compose.onNodeWithTag("search_input").performTextInput("Alex")
        dismissHeader()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        openChatSearch(compose)
        compose.onNodeWithTag("search_input").assertTextEquals("Alex")
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("Switch to Music Search").performClick()
        compose.onNodeWithTag("search_input").performTextInput("track")
        dismissHeader()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        openChatSearch(compose)
        compose.onNodeWithContentDescription("Switch to Music Search").performClick()
        compose.onNodeWithTag("search_input").assertTextEquals("track")
        compose.onNodeWithContentDescription("Search context locked").assertIsDisplayed()
    }
    @Test fun lowerResultsScrollWithoutDismissalAndCancelledHeaderPullsStayOpen() {
        openChatSearch(compose)
        val results = compose.onNodeWithTag("search_results")
        results.performScrollToIndex(5)
        results.performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + distance), 350) }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        val header = compose.onNodeWithTag("search_scope")
        header.performTouchInput { down(center); moveBy(Offset(0f, distance), 300); moveBy(Offset(0f, -distance), 300); up() }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        header.performTouchInput { down(center); moveBy(Offset(0f, distance), 300); cancel() }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        header.performTouchInput { down(0, center); moveBy(Offset(0f, distance), 300); down(1, center + Offset(10f, 0f)); up(1); up(0) }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        dismissHeader() // Header dismissal remains available even when results are lower.
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun androidBackHidesKeyboardBeforeClosingWorkspace() {
        openChatSearch(compose)
        compose.onNodeWithTag("search_input").performClick()
        compose.waitUntil(5000) { imeVisible() }
        back()
        compose.waitUntil(5000) { !imeVisible() }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        back()
        compose.waitUntil(3000) { compose.onAllNodesWithTag("search_workspace").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun searchFromMusicReturnsToChatsWithoutAddingAModuleVisit() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Music").performClick()
        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithText("No playlists yet").assertIsDisplayed()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        dismissHeader()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        back() // Reveal the unchanged history head (Music) without popping it.
        compose.waitUntil(3000) { compose.onAllNodesWithText("No playlists yet").fetchSemanticsNodes().isNotEmpty() }
        back() // Music's internal screen pops before module history.
        compose.onNodeWithText("Your offline library").assertIsDisplayed()
        back() // Original Music -> Chats history is still intact.
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
}
