package com.vector.messenger

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.messenger.feature.*
import androidx.compose.ui.semantics.SemanticsProperties
import org.junit.*

class TerminalSearchUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun defaults() = appearance.before()
    @After fun restore() = appearance.after()
    private fun fast(right: Boolean) {
        compose.onNodeWithTag("search_workspace").performTouchInput {
            val y = height * .65f
            swipe(Offset(width * if (right) .25f else .75f, y), Offset(width * if (right) .75f else .25f, y), 100)
        }
    }
    @Test fun normalChatsHasNoSearchControlsAndPullOpensOnRelease() {
        compose.onNodeWithTag("search_input").assertDoesNotExist()
        compose.onNodeWithContentDescription("Search").assertDoesNotExist()
        val list = compose.onNodeWithTag("chat_list")
        val short = with(compose.density) { 24.dp.toPx() }
        list.performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + short), 300) }
        compose.onNodeWithTag("search_workspace").assertDoesNotExist()
        val distance = with(compose.density) { 130.dp.toPx() }
        list.performTouchInput { down(Offset(center.x, 30f)); moveBy(Offset(0f, distance), 300) }
        compose.onNodeWithTag("search_workspace").assertDoesNotExist()
        list.performTouchInput { up() }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "chat-search")
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun fastSearchSwitchLockAndUnlockNeverOpenModuleMenu() {
        openChatSearch(compose)
        fast(true)
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "music-search")
        fast(false)
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("Mira")
        compose.onNodeWithContentDescription("Search context locked").assertIsDisplayed()
        fast(true)
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        compose.onNodeWithText("Mira Chen").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "search-locked")
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithContentDescription("Search context locked").assertDoesNotExist()
        fast(true)
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("song")
        fast(false)
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear search").performClick()
        fast(false)
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
    }
    @Test fun preferencesPersistAndDisabledPullHasAccessibleMenuAlternative() {
        compose.runOnUiThread {
            val viewModel = ViewModelProvider(compose.activity)[PreferencesViewModel::class.java]
            viewModel.appearance { it.copy(pullSearch = false, pullDistance = 96, autoLock = false) }
            val restored = AppearanceStore(compose.activity)
            Assert.assertFalse(restored.state.value.pullSearch)
            Assert.assertEquals(96, restored.state.value.pullDistance)
        }
        val distance = with(compose.density) { 130.dp.toPx() }
        compose.onNodeWithTag("chat_list").performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + distance), 400) }
        compose.onNodeWithTag("search_workspace").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("Mira")
        compose.onNodeWithContentDescription("Search context locked").assertDoesNotExist()
        fast(true)
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }
    @Test fun holdInSearchActivatesModuleNavigationWithoutFastSwitching() {
        openChatSearch(compose)
        val distance = with(compose.density) { 32.dp.toPx() }
        val workspace = compose.onNodeWithTag("search_workspace")
        workspace.performTouchInput { down(Offset(width * .35f, height * .65f)); moveBy(Offset(distance, 0f)) }
        compose.waitUntil(3000) { compose.onNodeWithTag("header_gesture_surface").fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Activated" }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        workspace.performTouchInput { up() }
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        compose.onNodeWithText("Music Search").assertDoesNotExist()
    }
    @Test fun blockedFastSwipeMovesAtMostEightDpAndReturns() {
        openChatSearch(compose)
        compose.onNodeWithTag("search_input").performTextInput("Mira")
        val workspace = compose.onNodeWithTag("search_workspace")
        val original = workspace.fetchSemanticsNode().boundsInRoot.left
        compose.mainClock.autoAdvance = false
        try {
            fast(true)
            compose.mainClock.advanceTimeBy(96)
            val displacement = workspace.fetchSemanticsNode().boundsInRoot.left - original
            Assert.assertTrue("Locked swipe must give visible resistance", displacement > 0f)
            Assert.assertTrue(displacement <= with(compose.density) { 8.dp.toPx() } + 1f)
            compose.mainClock.advanceTimeBy(400)
            Assert.assertEquals(original, workspace.fetchSemanticsNode().boundsInRoot.left, .5f)
            compose.onNodeWithText("Chat Search").assertIsDisplayed()
        } finally { compose.mainClock.autoAdvance = true }
    }
    @Test fun appearanceControlsApplyAndPersistTheirActualValues() {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Settings").performClick()
        saveDesignScreenshot(compose.onRoot(), "settings")
        compose.onNodeWithContentDescription("Increase Pull-down activation distance").performScrollTo().performClick()
        compose.onNodeWithText("Reduced motion").performScrollTo().performClick().assertIsOn()
        compose.runOnUiThread {
            val restored = AppearanceStore(compose.activity)
            Assert.assertEquals(96, restored.state.value.pullDistance)
            Assert.assertTrue(restored.state.value.reducedMotion)
        }
        compose.onNodeWithText("Pull-down search").performScrollTo().performClick().assertIsOff()
        compose.runOnUiThread { Assert.assertFalse(AppearanceStore(compose.activity).state.value.pullSearch) }
    }
    @Test fun continuouslyMovingFastSwipeDoesNotBecomeAHoldAfter180Millis() {
        // Enter via semantics so the preceding pull does not advance synthetic event time
        // ahead of the native clock used by this one real-time input sequence.
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Search").performClick()
        compose.waitUntil(3000) { compose.activity.hasWindowFocus() }
        val workspace = compose.onNodeWithTag("search_workspace")
        val initial = with(compose.density) { 40.dp.toPx() }
        val step = with(compose.density) { 16.dp.toPx() }
        val bounds = workspace.fetchSemanticsNode().boundsInRoot
        val start = bounds.left + bounds.width * .2f
        val y = bounds.top + bounds.height * .65f
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var downTime = 0L
        var upTime = 0L
        val trace = java.util.concurrent.CopyOnWriteArrayList<String>()
        lateinit var original: android.view.Window.Callback
        compose.runOnUiThread {
            original = compose.activity.window.callback
            compose.activity.window.callback = object : android.view.Window.Callback by original {
                override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
                    trace += "${event.actionMasked}:${event.eventTime - event.downTime}ms/tool${event.getToolType(0)}@${event.x.toInt()},${event.y.toInt()}"
                    return original.dispatchTouchEvent(event)
                }
            }
        }
        fun inject(action: Int, x: Float) {
            val eventTime = android.os.SystemClock.uptimeMillis()
            if (action == android.view.MotionEvent.ACTION_UP) upTime = eventTime
            val pointer = android.view.MotionEvent.PointerProperties().apply { id = 0; toolType = android.view.MotionEvent.TOOL_TYPE_FINGER }
            val coordinates = android.view.MotionEvent.PointerCoords().apply { this.x = x; this.y = y; pressure = 1f; size = 1f }
            val event = android.view.MotionEvent.obtain(downTime, eventTime, action,
                1, arrayOf(pointer), arrayOf(coordinates), 0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0)
            try { Assert.assertTrue(instrumentation.uiAutomation.injectInputEvent(event, action == android.view.MotionEvent.ACTION_UP)) } finally { event.recycle() }
        }
        // Keep event timestamps and wall time on the same clock. Separate Compose
        // injection scopes also advance virtual time, which is unsuitable for this check.
        try {
        downTime = android.os.SystemClock.uptimeMillis()
        inject(android.view.MotionEvent.ACTION_DOWN, start)
        inject(android.view.MotionEvent.ACTION_MOVE, start + initial)
        val movementStart = android.os.SystemClock.uptimeMillis()
        repeat(10) { index -> Thread.sleep(18); inject(android.view.MotionEvent.ACTION_MOVE, start + initial + step * (index + 1)) }
        inject(android.view.MotionEvent.ACTION_UP, start + initial + step * 10)
        val elapsed = upTime - downTime
        Assert.assertTrue("Continuous travel must outlast the 180 ms hold timer", upTime - movementStart >= 180)
        Assert.assertTrue("Real test swipe must stay inside the fast-swipe window: $elapsed ms; $trace", elapsed in 200..350)
        try { compose.waitUntil(3000) { compose.onAllNodesWithText("Music Search").fetchSemanticsNodes().isNotEmpty() } }
        catch (_: ComposeTimeoutException) {
            saveDesignScreenshot(compose.onRoot(), "timing-diagnostic")
            Assert.fail("Native swipe did not switch scope; delivered events: $trace")
        }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        } finally { compose.runOnUiThread { compose.activity.window.callback = original } }
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
    }
}
