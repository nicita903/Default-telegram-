package com.vector.messenger

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createComposeRule
import com.vector.messenger.design.rememberInteractiveOffset
import com.vector.messenger.feature.AppearanceSettings
import com.vector.messenger.feature.LocalAppearance
import org.junit.*

class MotionLifecycleUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun pullFramesDoNotRecomposeTheParentAndRemovalCancelsReturn() {
        val raw = mutableFloatStateOf(0f)
        val dragging = mutableStateOf(false)
        val shown = mutableStateOf(true)
        var compositions = 0
        lateinit var offset: Animatable<Float, AnimationVector1D>
        compose.setContent {
            if (shown.value) {
                offset = rememberInteractiveOffset({ raw.floatValue }, { dragging.value })
                SideEffect { compositions++ }
                Box(Modifier.fillMaxSize().graphicsLayer { translationY = if (dragging.value) raw.floatValue else offset.value })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.waitForIdle()
        val before = compositions
        repeat(24) { frame ->
            compose.runOnIdle { dragging.value = true; raw.floatValue = (frame + 1).toFloat() }
            compose.mainClock.advanceTimeByFrame()
        }
        compose.runOnIdle { Assert.assertEquals(before, compositions); dragging.value = false; raw.floatValue = 0f }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { Assert.assertTrue(offset.isRunning); shown.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        val stopped = offset.value
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { Assert.assertFalse(offset.isRunning); Assert.assertEquals(stopped, offset.value, 0f) }
    }
    @Test fun reducedMotionPreferenceCancelsAnActiveReturnImmediately() {
        val settings = mutableStateOf(AppearanceSettings())
        val raw = mutableFloatStateOf(30f)
        val dragging = mutableStateOf(true)
        lateinit var offset: Animatable<Float, AnimationVector1D>
        compose.setContent {
            CompositionLocalProvider(LocalAppearance provides settings.value) {
                offset = rememberInteractiveOffset({ raw.floatValue }, { dragging.value })
                Box(Modifier.fillMaxSize().graphicsLayer { translationY = offset.value })
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { dragging.value = false; raw.floatValue = 0f }
        compose.mainClock.advanceTimeBy(32)
        compose.runOnIdle { Assert.assertTrue(offset.isRunning); settings.value = settings.value.copy(reducedMotion = true) }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { Assert.assertFalse(offset.isRunning); Assert.assertEquals(0f, offset.value, 0f) }
    }
}
