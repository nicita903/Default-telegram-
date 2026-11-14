package com.vector.messenger

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.vector.messenger.navigation.gesture.*
import org.junit.*

class GestureHostUiTest {
    @get:Rule val compose = createComposeRule()
    private val controller = VectorGestureController()
    private val excludedOffset = mutableFloatStateOf(0f)
    private val lateConsumer = mutableStateOf(false)
    private val policy = mutableStateOf(GestureCancelReason.None)
    private var actions = 0
    private var ticks = 0
    private fun content() {
        compose.setContent {
            val regions = remember { GestureExclusions() }
            val scale = animateFloatAsState(if (controller.activated) .95f else 1f)
            CompositionLocalProvider(LocalGestureExclusions provides regions, LocalHapticFeedback provides object : HapticFeedback {
                override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { ticks++ }
            }) {
                Box(Modifier.fillMaxSize().pointerInput(lateConsumer.value) {
                    if (!lateConsumer.value) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(false)
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            if (event.changes.none { it.pressed }) break
                            if (event.changes.any { (it.position - down.position).getDistance() > 1f }) event.changes.forEach { it.consume() }
                        }
                    }
                }) {
                    Box(Modifier.fillMaxSize().vectorModuleGestures(controller, { policy.value }, { actions++ }, scopeKey = policy.value).testTag("host")) {
                        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = scale.value; scaleY = scale.value }
                            .moduleGestureSurface(controller, ModuleGestureSurface.Content)) {
                            Box(Modifier.size(80.dp).align(androidx.compose.ui.Alignment.Center)
                                .graphicsLayer { translationX = excludedOffset.floatValue }
                                .excludeModuleGestures().testTag("excluded"))
                        }
                    }
                }
            }
        }
    }
    private fun activate(start: Offset, direction: Int = 1) {
        val exactDistance = with(compose.density) { 28.dp.toPx() }
        compose.onNodeWithTag("host").performTouchInput { down(start); moveBy(Offset(direction * exactDistance, 0f)) }
        compose.waitUntil(3000) { controller.activated }
        compose.runOnIdle { Assert.assertEquals(0, actions) }
    }
    @Test fun exactThresholdWorksRepeatedlyOutsideTheAnimatedSurface() {
        content()
        val host = compose.onNodeWithTag("host")
        repeat(10) { index ->
            val bounds = host.fetchSemanticsNode().boundsInRoot
            val start = Offset(bounds.width / 2, bounds.height / 4)
            val previousActions = actions
            val distance = with(compose.density) { 28.dp.toPx() }
            host.performTouchInput { down(start); moveBy(Offset(if (index % 2 == 0) distance else -distance, 0f)) }
            compose.waitUntil(3000) { controller.activated }
            compose.waitForIdle() // Allow the scaled child to settle with a stationary finger.
            Assert.assertEquals(previousActions, actions)
            Assert.assertEquals(index + 1, ticks)
            host.performTouchInput { moveBy(Offset(0f, 0f)); up() }
            compose.runOnIdle { Assert.assertEquals(previousActions + 1, actions); Assert.assertEquals(VectorGestureState.Idle, controller.state) }
        }
    }
    @Test fun exclusionsUseCurrentGraphicsTransformInsteadOfCachedLayoutBounds() {
        content()
        val original = compose.onNodeWithTag("excluded").fetchSemanticsNode().boundsInRoot.center
        compose.runOnIdle { excludedOffset.floatValue = with(compose.density) { 100.dp.toPx() } }
        val moved = compose.onNodeWithTag("excluded").fetchSemanticsNode().boundsInRoot.center
        val distance = with(compose.density) { 28.dp.toPx() }
        val host = compose.onNodeWithTag("host")
        host.performTouchInput { down(moved); moveBy(Offset(distance, 0f)) }
        Thread.sleep(230)
        host.performTouchInput { up() }
        compose.runOnIdle { Assert.assertEquals(0, actions); Assert.assertEquals(0, ticks) }
        activate(original)
        host.performTouchInput { up() }
        compose.runOnIdle { Assert.assertEquals(1, actions) }
    }
    @Test fun finalPassOwnershipAndMidHoldEligibilityCancelWithoutStaleActivation() {
        content()
        val host = compose.onNodeWithTag("host")
        val bounds = host.fetchSemanticsNode().boundsInRoot
        val start = Offset(bounds.width / 2, bounds.height / 4)
        compose.runOnIdle { lateConsumer.value = true }
        val preThreshold = with(compose.density) { 10.dp.toPx() }
        host.performTouchInput { down(start); moveBy(Offset(preThreshold, 0f)) }
        Thread.sleep(230)
        host.performTouchInput { up() }
        compose.runOnIdle { Assert.assertEquals(0, actions); Assert.assertEquals(0, ticks); lateConsumer.value = false }
        val distance = with(compose.density) { 28.dp.toPx() }
        host.performTouchInput { down(start); moveBy(Offset(distance, 0f)) }
        compose.runOnIdle { policy.value = GestureCancelReason.SearchActive }
        Thread.sleep(230)
        host.performTouchInput { up() }
        compose.runOnIdle { Assert.assertEquals(0, actions); Assert.assertEquals(0, ticks); policy.value = GestureCancelReason.None }
        activate(start)
        host.performTouchInput { up() }
        compose.runOnIdle { Assert.assertEquals(1, actions); Assert.assertEquals(1, ticks) }
    }
}
