package com.vector.messenger

import com.vector.messenger.feature.*
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.navigation.gesture.*
import org.junit.Assert.*
import org.junit.Test

class AppearanceSettingsTest {
    @Test fun allNumericPreferencesAreBoundedAndNormalizationIsIdempotent() {
        val low = AppearanceSettings(distance = -1, hold = 0, zoom = -1, speed = 0, pullDistance = 0,
            sensitivity = 0, accent = -1, intensity = -1, typeScale = 0).normalized()
        assertEquals(listOf(20, 120, 0, 50, 48, 70, 0, 0, 85),
            listOf(low.distance, low.hold, low.zoom, low.speed, low.pullDistance, low.sensitivity, low.accent, low.intensity, low.typeScale))
        val high = AppearanceSettings(distance = 999, hold = 999, zoom = 999, speed = 999, pullDistance = 999,
            sensitivity = 999, accent = 999, intensity = 999, typeScale = 999).normalized()
        assertEquals(listOf(60, 400, 10, 150, 120, 140, 2, 100, 130),
            listOf(high.distance, high.hold, high.zoom, high.speed, high.pullDistance, high.sensitivity, high.accent, high.intensity, high.typeScale))
        assertEquals(high, high.normalized())
        assertEquals(AppearanceSettings(), AppearanceSettings().normalized())
    }
    @Test fun systemReducedMotionOverridesWithoutChangingStoredIntent() {
        val saved = AppearanceSettings(reducedMotion = false)
        val effective = saved.respectingSystemMotion(false)
        assertTrue(effective.reducedMotion)
        assertEquals(0, VectorMotionSpec.duration(180, effective))
        assertEquals(1f, VectorMotionSpec.surfaceScale(true, effective), 0f)
        assertFalse(saved.reducedMotion)
        assertFalse(saved.respectingSystemMotion(true).reducedMotion)
        assertTrue(saved.copy(reducedMotion = true).respectingSystemMotion(true).reducedMotion)
    }
    @Test fun moduleDistanceAndHoldSettingsDriveRecognitionIndependently() {
        for (distance in listOf(20, 28, 60)) for (hold in listOf(120, 180, 400)) {
            val settings = AppearanceSettings(distance = distance, hold = hold)
            val recognizer = VectorGestureRecognizer(GestureThresholds(settings.distance.toFloat(), 8f, 20f, 14f))
            recognizer.down(); recognizer.move(distance - .1f, 0f)
            assertEquals(VectorGestureState.Tracking, recognizer.state)
            recognizer.move(distance.toFloat(), 0f); recognizer.beginHold(1000, settings.hold.toLong())
            assertFalse(recognizer.activate(1000L + hold - 1))
            assertTrue(recognizer.activate(1000L + hold))
            assertEquals(VectorGestureDirection.Right, recognizer.release())
            assertNull(recognizer.release())
        }
    }
}
