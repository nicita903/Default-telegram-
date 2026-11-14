package com.vector.messenger.navigation.gesture

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class VectorGestureConfig(
    val distance: Dp = 28.dp,
    val holdMillis: Long = 180,
    val verticalTolerance: Dp = 20.dp,
    val reverseTolerance: Dp = 14.dp,
    val edgeExclusion: Dp = 24.dp,
) { init { require(distance.value > 0 && holdMillis > 0) } }

@Stable
class VectorGestureController {
    var state by mutableStateOf(VectorGestureState.Idle)
        internal set
    val activated get() = state == VectorGestureState.Activated
}

enum class ModuleGestureSurface { Header, Content }

/** Live geometry, read only at pointer down. Layout/scale changes never recompose the host. */
class GestureExclusions {
    private val exclusions = mutableMapOf<Any, LayoutCoordinates>()
    private val surfaces = mutableMapOf<Any, Pair<LayoutCoordinates, ModuleGestureSurface>>()
    fun put(key: Any, coordinates: LayoutCoordinates) { exclusions[key] = coordinates }
    fun remove(key: Any) { exclusions.remove(key) }
    fun putSurface(key: Any, coordinates: LayoutCoordinates, kind: ModuleGestureSurface) { surfaces[key] = coordinates to kind }
    fun removeSurface(key: Any) { surfaces.remove(key) }
    fun contains(position: Offset) = exclusions.values.any { it.isAttached && it.boundsInRoot().contains(position) }
    fun surfaceAt(position: Offset): ModuleGestureSurface? = surfaces.values.firstOrNull {
        it.first.isAttached && it.first.boundsInRoot().contains(position)
    }?.second
}
val LocalGestureExclusions = staticCompositionLocalOf { GestureExclusions() }

fun Modifier.excludeModuleGestures(): Modifier = composed {
    val regions = LocalGestureExclusions.current
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.remove(key) } }
    onGloballyPositioned { regions.put(key, it) }
}

/** Eligibility/semantics only: there is no pointer detector on a registered child surface. */
fun Modifier.moduleGestureSurface(controller: VectorGestureController, kind: ModuleGestureSurface): Modifier = composed {
    val regions = LocalGestureExclusions.current
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.removeSurface(key) } }
    onGloballyPositioned { regions.putSurface(key, it, kind) }
        .semantics { stateDescription = controller.state.name }
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

private class GestureHostCoordinates { var value: LayoutCoordinates? = null }

/** One module owner outside the scaled Scaffold. Search installs its own exclusive owner. */
fun Modifier.vectorModuleGestures(
    controller: VectorGestureController,
    eligibility: () -> GestureCancelReason,
    onRelease: (VectorGestureDirection) -> Unit,
    config: VectorGestureConfig = VectorGestureConfig(),
    scopeKey: Any? = null,
    haptics: Boolean = true,
    enabled: Boolean = true,
): Modifier = composed {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val regions = LocalGestureExclusions.current
    val haptic = LocalHapticFeedback.current
    val policy by rememberUpdatedState(eligibility)
    val action by rememberUpdatedState(onRelease)
    val thresholds = remember(config, density.density, viewConfiguration.touchSlop) {
        with(density) { GestureThresholds(config.distance.toPx(), viewConfiguration.touchSlop.coerceAtMost(config.distance.toPx()), config.verticalTolerance.toPx(), config.reverseTolerance.toPx()) }
    }
    val edge = with(density) { config.edgeExclusion.toPx() }
    val systemLeft = WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current).toFloat()
    val systemRight = WindowInsets.systemGestures.getRight(density, LocalLayoutDirection.current).toFloat()
    val coordinates = remember { GestureHostCoordinates() }
    onGloballyPositioned { coordinates.value = it }
        .pointerInput(controller, thresholds, config.holdMillis, edge, systemLeft, systemRight, scopeKey, enabled, haptics) {
            if (!enabled) {
                GestureDiagnostics.record(VectorGestureState.Idle, null, config.distance.value, config.holdMillis, policy())
                return@pointerInput
            }
            val machine = VectorGestureRecognizer(thresholds)
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val startInRoot = coordinates.value?.localToRoot(down.position) ?: down.position
                val surface = regions.surfaceAt(startInRoot)
                val moduleAllowed = policy() == GestureCancelReason.None
                val rejected = when {
                    down.type != PointerType.Touch -> GestureCancelReason.UnsupportedPointer
                    down.position.x < maxOf(edge, systemLeft) || down.position.x > size.width - maxOf(edge, systemRight) -> GestureCancelReason.SystemEdge
                    regions.contains(startInRoot) -> GestureCancelReason.ExcludedRegion
                    surface == null -> GestureCancelReason.OutsideSurface
                    !moduleAllowed -> policy()
                    else -> GestureCancelReason.None
                }
                if (rejected != GestureCancelReason.None) {
                    GestureDiagnostics.record(VectorGestureState.Cancelled, null, config.distance.value, config.holdMillis, rejected)
                    return@awaitEachGesture
                }
                if (!moduleAllowed) GestureDiagnostics.record(VectorGestureState.Idle, null, config.distance.value, config.holdMillis, policy())
                // Root local coordinates are physical and unscaled throughout the sequence.
                val start = down.position
                machine.down()
                var publishedState: VectorGestureState? = null
                var publishedDirection: VectorGestureDirection? = null
                fun publish(action: GestureNavigationAction = GestureNavigationAction.None) {
                    if (moduleAllowed) controller.state = machine.state
                    if (publishedState != machine.state || publishedDirection != machine.direction || action != GestureNavigationAction.None) {
                        GestureDiagnostics.record(machine.state, machine.direction, config.distance.value, config.holdMillis, machine.cancellation, action)
                        publishedState = machine.state; publishedDirection = machine.direction
                    }
                }
                fun activateIfDue() {
                    if (moduleAllowed && policy() == GestureCancelReason.None && machine.activate(SystemClock.uptimeMillis())) {
                        publish()
                        if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    }
                }
                publish()
                try {
                    while (true) {
                        val deadline = machine.holdDeadlineMillis.takeIf { moduleAllowed }
                        val event = if (deadline != null) {
                            val remaining = deadline - SystemClock.uptimeMillis()
                            if (remaining > 0) withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Main) } else null
                        } else awaitPointerEvent(PointerEventPass.Main)
                        if (moduleAllowed && policy() != GestureCancelReason.None) { machine.cancel(policy()); publish(); break }
                        if (event == null) {
                            activateIfDue()
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null) { machine.cancel(GestureCancelReason.PointerLost); publish(); break }
                        if (event.changes.size != 1) { machine.cancel(GestureCancelReason.MultiplePointers); publish(); break }
                        if (change.isConsumed) {
                            machine.cancel(if (!change.pressed) GestureCancelReason.PointerCancelled else GestureCancelReason.ChildConsumed)
                            publish(); break
                        }
                        val dx = change.position.x - start.x
                        val dy = change.position.y - start.y
                        machine.move(dx, dy)
                        publish()
                        if (machine.state == VectorGestureState.Cancelled) break
                        if (!change.pressed) {
                            // A valid up and the deadline can arrive in the same dispatch turn.
                            // Resolve the deadline first, without a second haptic/activation.
                            activateIfDue()
                            val direction = machine.release()
                            if (moduleAllowed && direction != null && policy() == GestureCancelReason.None) {
                                change.consume()
                                publish(if (direction == VectorGestureDirection.Right) GestureNavigationAction.OpenModules else GestureNavigationAction.PreviousModule)
                                action(direction)
                            } else publish()
                            break
                        }
                        if (machine.state == VectorGestureState.ThresholdReached) {
                            // Hold scheduling uses one clock domain. Pointer event timestamps
                            // can be buffered or synthetic; they must not pre-expire the timer.
                            machine.beginHold(SystemClock.uptimeMillis(), config.holdMillis)
                            publish()
                        }
                        val owned = machine.state == VectorGestureState.Holding || machine.state == VectorGestureState.Activated
                        if (owned) change.consume()
                        // A child or another ancestor can claim during Main after pre-slop tracking.
                        // Inspect Final before accepting a later threshold or starting a hold.
                        val final = awaitPointerEvent(PointerEventPass.Final)
                        if (!owned && final.changes.any { it.id == down.id && it.isConsumed }) {
                            machine.cancel(GestureCancelReason.ChildConsumed); publish(); break
                        }
                    }
                } finally {
                    if (machine.state != VectorGestureState.Idle && machine.state != VectorGestureState.Cancelled) {
                        val reason = policy().takeIf { it != GestureCancelReason.None } ?: GestureCancelReason.ScopeChanged
                        machine.cancel(reason); publish()
                    }
                    machine.reset()
                    controller.state = VectorGestureState.Idle
                    publish()
                }
            }
        }
}
