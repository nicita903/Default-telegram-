package com.vector.messenger

import com.vector.messenger.navigation.gesture.*
import com.vector.messenger.feature.search.searchLocked
import org.junit.Assert.*
import org.junit.Test

class SearchGestureTest {
    private fun fast(dx: Float = 100f, dy: Float = 0f, elapsed: Long = 100, velocity: Float = 1000f) =
        isFastSearchSwipe(dx, dy, elapsed, velocity, 56f, 600f)
    @Test fun fastSwipeRequiresDistanceDirectionVelocityAndTime() {
        assertTrue(fast())
        assertTrue(fast(dx = -100f, velocity = -1000f))
        assertFalse(fast(dx = 30f))
        assertFalse(fast(dy = 80f))
        assertFalse(fast(elapsed = 500))
        assertFalse(fast(velocity = 200f))
        assertFalse(fast(velocity = -1000f))
        assertFalse(fast(elapsed = 0))
    }
    @Test fun automaticLockIncludesWhitespaceAndClearsOnlyWhenEmpty() {
        assertTrue(searchLocked("Mira", true))
        assertTrue(searchLocked(" ", true))
        assertFalse(searchLocked("", true))
        assertFalse(searchLocked("Mira", false))
    }
    @Test fun heldOrCancelledGestureCannotProduceReleaseNavigationTwice() {
        val recognizer = VectorGestureRecognizer(GestureThresholds(28f, 8f, 20f, 14f))
        recognizer.down(); recognizer.move(32f, 0f); recognizer.beginHold(); recognizer.activate()
        assertEquals(VectorGestureDirection.Right, recognizer.release())
        assertNull(recognizer.release())
        recognizer.down(); recognizer.move(100f, 0f); recognizer.cancel()
        assertNull(recognizer.release())
    }
}
