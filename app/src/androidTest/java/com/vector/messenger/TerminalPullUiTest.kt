package com.vector.messenger

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.navigation.gesture.*
import org.junit.*

/** Long test-only list exercises the real adapter without changing the user's chat repository. */
class TerminalPullUiTest {
    @get:Rule val compose = createComposeRule()
    private var opens = 0
    private fun content() {
        compose.setContent {
            DefaultTheme {
                val list = rememberLazyListState()
                val pull = remember { PullSearchState() }
                Box(Modifier.fillMaxSize().pullToSearch(list, pull, true, 72, false) { opens++ }) {
                    LazyColumn(Modifier.fillMaxSize().testTag("long_list"), state = list) {
                        items(60, key = { it }) { Text("Row $it", Modifier.fillMaxWidth().height(72.dp)) }
                    }
                }
            }
        }
    }
    @Test fun pullStartingLowerScrollsWithoutOpeningEvenWhenItReachesTop() {
        content()
        val list = compose.onNodeWithTag("long_list")
        list.performScrollToIndex(3)
        list.performTouchInput { swipeDown() }
        compose.runOnIdle { Assert.assertEquals(0, opens) }
        list.performScrollToIndex(0)
        val distance = with(compose.density) { 130.dp.toPx() }
        list.performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + distance), 400) }
        compose.runOnIdle { Assert.assertEquals(1, opens) }
    }
    @Test fun reversalMultitouchAndSystemCancellationDoNotOpen() {
        content()
        val list = compose.onNodeWithTag("long_list")
        val distance = with(compose.density) { 130.dp.toPx() }
        list.performTouchInput { down(Offset(center.x, 30f)); moveBy(Offset(0f, distance), 300); moveBy(Offset(0f, -distance), 300); up() }
        compose.runOnIdle { Assert.assertEquals(0, opens) }
        list.performScrollToIndex(0)
        list.performTouchInput { down(0, Offset(center.x, 30f)); moveBy(Offset(0f, distance), 300); down(1, center); up(1); up(0) }
        compose.runOnIdle { Assert.assertEquals(0, opens) }
        list.performTouchInput { down(Offset(center.x, 30f)); moveBy(Offset(0f, distance), 300); cancel() }
        compose.runOnIdle { Assert.assertEquals(0, opens) }
    }
}
