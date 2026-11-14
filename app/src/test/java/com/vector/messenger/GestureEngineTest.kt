package com.vector.messenger

import com.vector.messenger.navigation.gesture.*
import org.junit.Assert.*
import org.junit.Test

class GestureEngineTest {
    private fun engine() = VectorGestureRecognizer(GestureThresholds(28f, 8f, 20f, 14f))
    @Test fun holdDeadlineStartsAtThresholdAndDoesNotRestartWithJitter() {
        val engine = engine()
        engine.down(); engine.move(27.99f, 0f)
        assertNull(engine.holdDeadlineMillis)
        engine.move(28f, 0f); engine.beginHold(1000)
        assertEquals(1180L, engine.holdDeadlineMillis)
        engine.move(29f, 2f); engine.beginHold(1100)
        assertEquals(1180L, engine.holdDeadlineMillis)
        assertFalse(engine.activate(1179))
        assertTrue(engine.activate(1180))
        assertFalse(engine.activate(1181))
        assertEquals(VectorGestureDirection.Right, engine.release())
        assertNull(engine.release())
    }
    @Test fun earlyReleaseAndAllPriorityCancellationsInvalidateDeadline() {
        val reasons = listOf(GestureCancelReason.SearchActive, GestureCancelReason.ChildConsumed,
            GestureCancelReason.MultiplePointers, GestureCancelReason.ScopeChanged, GestureCancelReason.PointerCancelled)
        for (reason in reasons) {
            val engine = engine()
            engine.down(); engine.move(28f, 0f); engine.beginHold(0)
            engine.cancel(reason)
            assertNull(engine.holdDeadlineMillis)
            assertFalse(engine.activate(10_000))
            assertNull(engine.release())
            assertEquals(reason, engine.cancellation)
        }
        val engine = engine()
        engine.down(); engine.move(28f, 0f); engine.beginHold(0); engine.release()
        assertEquals(GestureCancelReason.EarlyRelease, engine.cancellation)
        assertFalse(engine.activate(180))
    }
    @Test fun geometryNeverSubtractsTouchSlopOrAccumulatesAcrossAttempts() {
        for (density in listOf(1f, 1.25f, 2f, 2.625f, 3f, 4f)) {
            val engine = VectorGestureRecognizer(GestureThresholds(28 * density, 8 * density, 20 * density, 14 * density))
            repeat(100) { index ->
                engine.down(); engine.move(0f, 30 * density); engine.reset()
                val sign = if (index % 2 == 0) 1 else -1
                engine.down(); engine.move(sign * 27.9f * density, 0f)
                assertEquals(VectorGestureState.Tracking, engine.state)
                engine.move(sign * 28 * density, 0f); engine.beginHold(0)
                assertTrue(engine.activate(180))
                assertEquals(if (sign == 1) VectorGestureDirection.Right else VectorGestureDirection.Left, engine.release())
            }
        }
    }
    @Test fun explicitCancellationReasonsSurviveRelease() {
        val engine = engine()
        engine.down(); engine.move(4f, 12f); engine.release()
        assertEquals(GestureCancelReason.VerticalIntent, engine.cancellation)
        engine.down(); engine.move(40f, 0f); engine.beginHold(0); engine.activate(180); engine.move(25f, 0f); engine.release()
        assertEquals(GestureCancelReason.DirectionReversal, engine.cancellation)
    }
    @Test fun searchExclusionDominatesEveryCombinationOfOtherPreferences() {
        for (enabled in listOf(false, true)) for (modal in listOf(false, true)) for (accessibility in listOf(false, true)) {
            assertEquals(GestureCancelReason.SearchActive, moduleGestureEligibility(enabled, true, modal, accessibility))
        }
        assertEquals(GestureCancelReason.None, moduleGestureEligibility(true, false, false, false))
        assertEquals(GestureCancelReason.Disabled, moduleGestureEligibility(false, false, false, false))
        assertEquals(GestureCancelReason.TouchExploration, moduleGestureEligibility(true, false, false, true))
    }
}
