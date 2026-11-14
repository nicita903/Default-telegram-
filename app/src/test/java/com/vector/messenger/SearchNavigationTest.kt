package com.vector.messenger

import com.vector.messenger.feature.search.*
import org.junit.Assert.*
import org.junit.Test

class SearchNavigationTest {
    private fun engine(top: Boolean = true) = SearchGestureRecognizer(8f, 14f).also { it.down(top) }
    private fun SearchGestureRecognizer.up(dx: Float = 80f, dy: Float = 0f, time: Long = 100, velocity: Float = 800f, age: Long = 0, pull: Float = 0f) =
        release(dx, dy, time, velocity, age, pull, 72f, 48f, 450f, true)
    @Test fun shortFastSwipesUseDirectionDistanceVelocityAndReleaseOnly() {
        val right = engine(); right.move(48f, 1f)
        assertEquals(SearchAxis.Horizontal, right.axis)
        assertEquals(SearchGestureAction.NextScope, right.up(dx = 48f))
        assertEquals(SearchGestureAction.None, right.up())
        val left = engine(); left.move(-48f, 0f)
        assertEquals(SearchGestureAction.PreviousScope, left.up(dx = -48f, velocity = -800f))
    }
    @Test fun sparseSamplesCanUseAverageVelocityButHeldAndSlowGesturesDoNotSwitch() {
        val fast = engine(); fast.move(100f, 0f)
        assertEquals(SearchGestureAction.NextScope, fast.up(dx = 100f, time = 150, velocity = 0f))
        for ((time, age) in listOf(500L to 0L, 300L to 150L)) {
            val held = engine(); held.move(100f, 0f)
            assertEquals(SearchGestureAction.None, held.up(time = time, age = age))
        }
        val slow = engine(); slow.move(50f, 0f)
        assertEquals(SearchGestureAction.None, slow.up(dx = 50f, time = 300, velocity = 100f))
    }
    @Test fun dismissalRequiresTopThresholdAndOneValidRelease() {
        val pull = engine(); pull.move(0f, 80f)
        assertEquals(SearchGestureAction.Dismiss, pull.up(dx = 0f, dy = 80f, pull = 72f))
        assertEquals(SearchGestureAction.None, pull.up(dx = 0f, dy = 80f, pull = 72f))
        val lower = engine(false); lower.move(0f, 100f)
        assertEquals(SearchGestureAction.None, lower.up(dx = 0f, dy = 100f, pull = 100f))
        val short = engine(); short.move(0f, 60f)
        assertEquals(SearchGestureAction.None, short.up(dx = 0f, dy = 60f, pull = 60f))
    }
    @Test fun reversalCancellationAndVerticalLockNeverProduceScopeSwitch() {
        val reverse = engine(); reverse.move(80f, 0f); reverse.move(60f, 0f)
        assertEquals(SearchGestureAction.None, reverse.up())
        val cancel = engine(); cancel.move(80f, 0f); cancel.cancel()
        assertEquals(SearchGestureAction.None, cancel.up())
        val scroll = engine(false); scroll.move(0f, 20f); scroll.move(100f, 30f)
        assertEquals(SearchAxis.Vertical, scroll.axis)
        assertEquals(SearchGestureAction.None, scroll.up())
    }
}
