package com.vector.messenger

import androidx.compose.animation.core.*
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.feature.AppearanceSettings
import org.junit.Assert.*
import org.junit.Test

class MotionSpecTest {
    @Test fun speedAndReducedMotionApplyToAllCentralTimings() {
        val normal = AppearanceSettings()
        assertEquals(70, VectorMotionSpec.duration(70, normal))
        assertEquals(120, VectorMotionSpec.duration(180, normal.copy(speed = 150)))
        assertEquals(360, VectorMotionSpec.duration(180, normal.copy(speed = 50)))
        assertEquals(0, VectorMotionSpec.duration(180, normal.copy(reducedMotion = true)))
        assertEquals(0, VectorMotionSpec.duration(180, normal.copy(intensity = 0)))
        assertEquals(180L, VectorMotionSpec.highlightMillis(normal))
    }
    @Test fun spatialTargetsRespectIntensityWithoutChangingConfiguredZoom() {
        val normal = AppearanceSettings()
        assertEquals(.95f, VectorMotionSpec.surfaceScale(true, normal), .00001f)
        assertEquals(.975f, VectorMotionSpec.surfaceScale(true, normal.copy(intensity = 50)), .00001f)
        assertEquals(1f, VectorMotionSpec.surfaceScale(true, normal.copy(reducedMotion = true)), 0f)
        assertEquals(1f, VectorMotionSpec.pressScale(.96f, normal.copy(intensity = 0)), 0f)
        assertEquals(.98f, VectorMotionSpec.pressScale(.96f, normal.copy(intensity = 50)), .00001f)
    }
    @Test fun pressRespondsInTheFirstFrameAndSettlesWithoutOscillation() {
        val settings = AppearanceSettings()
        val press = TargetBasedAnimation(VectorMotionSpec.press(true, settings), Float.VectorConverter, 1f, VectorMotionSpec.ControlPress)
        assertTrue(press.getValueFromNanos(16_000_000) < .99f)
        assertEquals(70_000_000L, press.durationNanos)
        val release = TargetBasedAnimation(VectorMotionSpec.settle(settings), Float.VectorConverter, .96f, 1f)
        for (time in 0..600 step 8) {
            val value = release.getValueFromNanos(time * 1_000_000L)
            assertTrue(value >= .95999f && value <= 1.00001f)
        }
    }
    @Test fun disabledMotionHasNoRunningAnimationDuration() {
        for (settings in listOf(AppearanceSettings(reducedMotion = true), AppearanceSettings(intensity = 0))) {
            assertEquals(0L, TargetBasedAnimation(VectorMotionSpec.settle(settings), Float.VectorConverter, 0f, 20f).durationNanos)
            assertEquals(0L, TargetBasedAnimation(VectorMotionSpec.feedback<Float>(settings), Float.VectorConverter, 0f, 1f).durationNanos)
            assertEquals(0L, TargetBasedAnimation(VectorMotionSpec.lockPeak(settings), Float.VectorConverter, 0f, 8f).durationNanos)
        }
    }
    @Test fun replyFollowRetainsTheExistingInteractivePhysics() {
        for (speed in listOf(50, 100, 150)) {
            val settings = AppearanceSettings(speed = speed)
            val original = TargetBasedAnimation(spring<Float>(dampingRatio = .9f, stiffness = Spring.StiffnessMedium * speed / 100f), Float.VectorConverter, 0f, 48f)
            val retained = TargetBasedAnimation(VectorMotionSpec.replyFollow(settings), Float.VectorConverter, 0f, 48f)
            for (millis in 0..500 step 16) assertEquals(original.getValueFromNanos(millis * 1_000_000L), retained.getValueFromNanos(millis * 1_000_000L), 0f)
        }
    }
}
