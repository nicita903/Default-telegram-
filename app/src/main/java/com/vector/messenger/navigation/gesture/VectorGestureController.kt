package com.vector.messenger.navigation.gesture

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.input.pointer.util.VelocityTracker

@Immutable
data class VectorGestureConfig(
    val distance: Dp = 28.dp,
    val holdMillis: Long = 180,
    val verticalTolerance: Dp = 20.dp,
    val reverseTolerance: Dp = 14.dp,
    val edgeExclusion: Dp = 24.dp,
) { init { require(holdMillis > 0) } }

@Stable
class VectorGestureController {
    var state by mutableStateOf(VectorGestureState.Idle)
        internal set
    val activated get() = state == VectorGestureState.Activated
}

/** Geometry only; changing scroll positions does not invalidate composition. */
class GestureExclusions {
    private val regions = mutableMapOf<Any, Rect>()
    fun put(key: Any, bounds: Rect) { regions[key] = bounds }
    fun remove(key: Any) { regions.remove(key) }
    fun contains(position: Offset) = regions.values.any { it.contains(position) }
}
val LocalGestureExclusions = staticCompositionLocalOf { GestureExclusions() }

fun Modifier.excludeModuleGestures(): Modifier = composed {
    val regions = LocalGestureExclusions.current
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.remove(key) } }
    onGloballyPositioned { regions.put(key, it.boundsInRoot()) }
}

@Composable
fun rememberTouchExplorationEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager }
    var enabled by remember { mutableStateOf(manager.isTouchExplorationEnabled) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager.addTouchExplorationStateChangeListener(listener)
        onDispose { manager.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

/** Applied to module content and its header, never dialogs, media, messages, composer, or system edges. */
fun Modifier.vectorModuleGestures(
    controller: VectorGestureController,
    enabled: Boolean,
    onRelease: (VectorGestureDirection) -> Unit,
    config: VectorGestureConfig = VectorGestureConfig(),
    scopeKey: Any? = null,
    haptics: Boolean = true,
    fastSearch: Boolean = false,
    fastSensitivity: Int = 100,
    onFastSwipe: (VectorGestureDirection) -> Unit = {},
): Modifier = composed {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val regions = LocalGestureExclusions.current
    val haptic = LocalHapticFeedback.current
    val action by rememberUpdatedState(onRelease)
    val fastAction by rememberUpdatedState(onFastSwipe)
    val fastDistance = with(density) { (56f * 100 / fastSensitivity).dp.toPx() }
    val fastVelocity = with(density) { (600f * 100 / fastSensitivity).dp.toPx() }
    val thresholds = remember(config, density, viewConfiguration.touchSlop) {
        with(density) { GestureThresholds(config.distance.toPx(), viewConfiguration.touchSlop.coerceAtMost(config.distance.toPx()), config.verticalTolerance.toPx(), config.reverseTolerance.toPx()) }
    }
    val edge = with(density) { config.edgeExclusion.toPx() }
    val systemLeft = WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current).toFloat()
    val systemRight = WindowInsets.systemGestures.getRight(density, LocalLayoutDirection.current).toFloat()
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    onGloballyPositioned { coordinates = it }
        .semantics { stateDescription = controller.state.name }
        .pointerInput(controller, enabled, thresholds, config.holdMillis, edge, systemLeft, systemRight, scopeKey, fastSearch, fastSensitivity, haptics) {
            if (!enabled && !fastSearch) return@pointerInput
            coroutineScope {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    // Root-space coordinates stay stable while the content scales under the finger.
                    val start = coordinates?.localToRoot(down.position) ?: down.position
                    if (down.type != PointerType.Touch ||
                        down.position.x < maxOf(edge, systemLeft) || down.position.x > size.width - maxOf(edge, systemRight) ||
                        regions.contains(start)) return@awaitEachGesture
                    val recognizer = VectorGestureRecognizer(thresholds)
                    recognizer.down()
                    val velocity = VelocityTracker()
                    velocity.addPosition(down.uptimeMillis, start)
                    controller.state = recognizer.state
                    var holdJob: Job? = null
                    var holdPosition = start
                    fun armHold() {
                        if (!enabled) return
                        holdJob?.cancel()
                        holdJob = launch {
                            delay(config.holdMillis)
                            if (recognizer.activate()) {
                                controller.state = recognizer.state
                                if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        }
                    }
                    try {
                        while (true) {
                            // Main pass reaches children first: scrolling, selection and local swipes win.
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            val position = coordinates?.localToRoot(change.position) ?: change.position
                            velocity.addPosition(change.uptimeMillis, position)
                            if (event.changes.size != 1 || change.isConsumed) { recognizer.cancel(); break }
                            if (!change.pressed) {
                                // Validate final coordinates too; a reversal on release cancels.
                                recognizer.move(position.x - start.x, position.y - start.y)
                                val cancelled = recognizer.state == VectorGestureState.Cancelled
                                val direction = recognizer.release()
                                if (direction != null) { change.consume(); action(direction) }
                                else if (!cancelled && fastSearch && isFastSearchSwipe(position.x - start.x, position.y - start.y,
                                        change.uptimeMillis - down.uptimeMillis, velocity.calculateVelocity().x, fastDistance, fastVelocity)) {
                                    change.consume()
                                    fastAction(if (position.x > start.x) VectorGestureDirection.Right else VectorGestureDirection.Left)
                                }
                                break
                            }
                            recognizer.move(position.x - start.x, position.y - start.y)
                            if (recognizer.state == VectorGestureState.Cancelled) break
                            if (recognizer.state == VectorGestureState.ThresholdReached) {
                                recognizer.beginHold()
                                holdPosition = position
                                armHold()
                            } else if (fastSearch && recognizer.state == VectorGestureState.Holding &&
                                (position - holdPosition).getDistance() > thresholds.touchSlop / 4f) {
                                // In Search, continuous horizontal travel belongs to the fast
                                // candidate. Module activation requires a deliberate pause.
                                holdPosition = position
                                armHold()
                            }
                            // Consume only after horizontal intent and the 28dp threshold are established.
                            if (recognizer.state == VectorGestureState.Holding || recognizer.state == VectorGestureState.Activated) change.consume()
                            controller.state = recognizer.state
                        }
                    } finally {
                        holdJob?.cancel()
                        recognizer.reset()
                        controller.state = VectorGestureState.Idle
                    }
                }
            }
        }
}
