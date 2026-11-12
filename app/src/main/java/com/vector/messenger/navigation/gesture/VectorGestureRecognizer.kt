package com.vector.messenger.navigation.gesture

import kotlin.math.abs
import kotlin.math.max

enum class VectorGestureState { Idle, Tracking, ThresholdReached, Holding, Activated, Cancelled }
enum class VectorGestureDirection { Left, Right }

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

    fun down() { state = VectorGestureState.Tracking; direction = null; peak = 0f }
    fun move(dx: Float, dy: Float, pointerCount: Int = 1, consumed: Boolean = false) {
        if (state == VectorGestureState.Idle || state == VectorGestureState.Cancelled) return
        if (pointerCount != 1 || consumed) { cancel(); return }
        if (abs(dy) > thresholds.verticalTolerance ||
            (direction == null && abs(dy) > thresholds.touchSlop && abs(dy) >= abs(dx))) {
            cancel(); return
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
        if (peak - signedDistance > thresholds.reverseTolerance) { cancel(); return }
        if (state == VectorGestureState.Tracking && signedDistance >= thresholds.distance) {
            state = VectorGestureState.ThresholdReached
        }
    }
    fun beginHold() { if (state == VectorGestureState.ThresholdReached) state = VectorGestureState.Holding }
    fun activate(): Boolean {
        if (state != VectorGestureState.Holding) return false
        state = VectorGestureState.Activated
        return true
    }
    fun release(): VectorGestureDirection? {
        val action = direction.takeIf { state == VectorGestureState.Activated }
        state = if (action == null) VectorGestureState.Cancelled else VectorGestureState.Idle
        return action
    }
    fun cancel() { state = VectorGestureState.Cancelled }
    fun reset() { state = VectorGestureState.Idle; direction = null; peak = 0f }
}
