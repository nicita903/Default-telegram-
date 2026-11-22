package com.vector.messenger.navigation.gesture

import kotlin.math.abs
import kotlin.math.max

enum class VectorGestureState { Idle, Tracking, ThresholdReached, Holding, Activated, Cancelled }
enum class VectorGestureDirection { Left, Right }
enum class GestureCancelReason {
    None, EarlyRelease, VerticalIntent, DirectionReversal, MultiplePointers, ChildConsumed,
    PointerLost, PointerCancelled, ScopeChanged, Disabled, SearchActive, ModalOpen,
    TouchExploration, SystemEdge, ExcludedRegion, OutsideSurface, UnsupportedPointer,
}

/** All distances are pixels. The Compose adapter converts configurable dp values exactly once. */
data class GestureThresholds(
    val distance: Float,
    val touchSlop: Float,
    val verticalTolerance: Float,
    val reverseTolerance: Float,
    val directionRatio: Float = 1.5f,
) {
    init { require(distance > 0 && touchSlop > 0 && verticalTolerance > 0 && reverseTolerance > 0 && directionRatio > 1) }
}

/** No Android, clocks, jobs, or navigation: deterministic input state machine. */
class VectorGestureRecognizer(private val thresholds: GestureThresholds) {
    var state = VectorGestureState.Idle
        private set
    var direction: VectorGestureDirection? = null
        private set
    private var peak = 0f
    var cancellation = GestureCancelReason.None
        private set
    var holdDeadlineMillis: Long? = null
        private set

    fun down() { reset(); state = VectorGestureState.Tracking }
    fun move(dx: Float, dy: Float, pointerCount: Int = 1, consumed: Boolean = false) {
        if (state == VectorGestureState.Idle || state == VectorGestureState.Cancelled) return
        if (pointerCount != 1) { cancel(GestureCancelReason.MultiplePointers); return }
        if (consumed) { cancel(GestureCancelReason.ChildConsumed); return }
        // Once horizontal intent is locked, judge drift against the direction as well as
        // the tolerance. An absolute 20 dp cap rejected clear horizontal diagonal swipes.
        val verticalIntent = if (direction == null)
            abs(dy) > thresholds.touchSlop && abs(dy) >= abs(dx)
        else abs(dy) > thresholds.verticalTolerance && abs(dy) * thresholds.directionRatio >= abs(dx)
        if (verticalIntent) {
            cancel(GestureCancelReason.VerticalIntent); return
        }
        if (direction == null && abs(dx) >= thresholds.touchSlop && abs(dx) > abs(dy) * thresholds.directionRatio) {
            direction = if (dx > 0) VectorGestureDirection.Right else VectorGestureDirection.Left
        }
        val signedDistance = when (direction) {
            VectorGestureDirection.Right -> dx
            VectorGestureDirection.Left -> -dx
            null -> return
        }
        peak = max(peak, signedDistance)
        if (peak - signedDistance > thresholds.reverseTolerance) { cancel(GestureCancelReason.DirectionReversal); return }
        if (state == VectorGestureState.Tracking && signedDistance >= thresholds.distance) {
            state = VectorGestureState.ThresholdReached
        }
    }
    fun beginHold(nowMillis: Long, holdMillis: Long = 180) {
        require(holdMillis > 0)
        if (state != VectorGestureState.ThresholdReached) return
        holdDeadlineMillis = nowMillis + holdMillis
        state = VectorGestureState.Holding
    }
    fun activate(nowMillis: Long): Boolean {
        if (state != VectorGestureState.Holding || nowMillis < (holdDeadlineMillis ?: return false)) return false
        state = VectorGestureState.Activated
        holdDeadlineMillis = null
        return true
    }
    fun release(): VectorGestureDirection? {
        val action = direction.takeIf { state == VectorGestureState.Activated }
        if (action == null) {
            if (state != VectorGestureState.Cancelled) cancel(GestureCancelReason.EarlyRelease)
        } else { state = VectorGestureState.Idle; holdDeadlineMillis = null }
        return action
    }
    fun cancel(reason: GestureCancelReason = GestureCancelReason.PointerCancelled) {
        if (state == VectorGestureState.Cancelled) return
        cancellation = reason; holdDeadlineMillis = null; state = VectorGestureState.Cancelled
    }
    fun reset() {
        state = VectorGestureState.Idle; direction = null; peak = 0f
        cancellation = GestureCancelReason.None; holdDeadlineMillis = null
    }
}
