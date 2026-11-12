package com.vector.messenger

import com.vector.messenger.navigation.*
import com.vector.messenger.navigation.gesture.*
import org.junit.Assert.*
import org.junit.Test

class VectorNavigationTest {
    private fun recognizer() = VectorGestureRecognizer(GestureThresholds(28f, 8f, 20f, 14f))
    @Test fun moduleBackPopsVisitHistoryWithoutCreatingLoops() {
        val controller = VectorNavigationController(VectorModuleHistory())
        controller.select(AppModule.Music)
        controller.select(AppModule.Privacy)
        controller.select(AppModule.Privacy)
        assertEquals(3, controller.history.visits.size)
        assertTrue(controller.previous())
        assertEquals(AppModule.Music, controller.module)
        assertTrue(controller.previous())
        assertEquals(AppModule.Chats, controller.module)
        assertFalse(controller.previous())
    }
    @Test fun historyKeepsRepeatedVisitsButBoundsMemory() {
        var history = VectorModuleHistory()
        repeat(100) { history = history.visit(if (it % 2 == 0) AppModule.Music else AppModule.Chats) }
        assertEquals(32, history.visits.size)
        assertEquals(AppModule.Music, history.previous().current)
    }
    @Test fun thresholdAndHoldDoNotNavigateUntilRelease() {
        val machine = recognizer()
        machine.down(); machine.move(27f, 0f)
        assertEquals(VectorGestureState.Tracking, machine.state)
        machine.move(28f, 0f)
        assertEquals(VectorGestureState.ThresholdReached, machine.state)
        machine.beginHold()
        assertTrue(machine.activate())
        assertFalse(machine.activate()) // Only one activation/haptic per sequence.
        assertEquals(VectorGestureState.Activated, machine.state)
        assertEquals(VectorGestureDirection.Right, machine.release())
        assertNull(machine.release()) // No second navigation.
    }
    @Test fun leftGestureUsesTheSameThreshold() {
        val machine = recognizer()
        machine.down(); machine.move(-28f, 1f); machine.beginHold(); machine.activate()
        assertEquals(VectorGestureDirection.Left, machine.release())
    }
    @Test fun earlyReleaseNeverNavigates() {
        val machine = recognizer()
        machine.down(); machine.move(30f, 0f); machine.beginHold()
        assertNull(machine.release())
        assertFalse(machine.activate())
    }
    @Test fun verticalIntentWinsBeforeAndAfterThreshold() {
        val machine = recognizer()
        machine.down(); machine.move(4f, 9f)
        assertEquals(VectorGestureState.Cancelled, machine.state)
        machine.reset(); machine.down(); machine.move(30f, 0f); machine.beginHold(); machine.activate(); machine.move(30f, 21f)
        assertNull(machine.release())
    }
    @Test fun reversalCancelsEvenAfterActivation() {
        val machine = recognizer()
        machine.down(); machine.move(40f, 0f); machine.beginHold(); machine.activate(); machine.move(25f, 0f)
        assertNull(machine.release())
    }
    @Test fun multiplePointersAndConsumedChildMovementCancel() {
        for (consumed in listOf(false, true)) {
            val machine = recognizer()
            machine.down(); machine.move(28f, 0f); machine.beginHold()
            machine.move(30f, 0f, pointerCount = if (consumed) 1 else 2, consumed = consumed)
            assertFalse(machine.activate())
            assertNull(machine.release())
        }
    }
    @Test fun cancelledSequenceDoesNotPoisonNextAttempt() {
        val machine = recognizer()
        repeat(10) {
            machine.down(); machine.move(0f, 30f); machine.reset()
            machine.down(); machine.move(28f, 0f); machine.beginHold(); assertTrue(machine.activate())
            assertEquals(VectorGestureDirection.Right, machine.release()); machine.reset()
        }
    }
    @Test fun pixelThresholdScalesWithDensity() {
        val machine = VectorGestureRecognizer(GestureThresholds(84f, 24f, 60f, 42f))
        machine.down(); machine.move(28f, 0f)
        assertEquals(VectorGestureState.Tracking, machine.state)
        machine.move(84f, 0f)
        assertEquals(VectorGestureState.ThresholdReached, machine.state)
    }
}
