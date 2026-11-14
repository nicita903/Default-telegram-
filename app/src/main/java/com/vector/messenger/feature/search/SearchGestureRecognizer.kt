package com.vector.messenger.feature.search

import kotlin.math.abs
import kotlin.math.max

enum class SearchAxis { Tracking, Horizontal, Vertical, Cancelled }
enum class SearchGestureAction { None, Dismiss, PreviousScope, NextScope }

/** Pure, release-only search decisions. Distances are pixels, converted by the Compose adapter. */
class SearchGestureRecognizer(private val slop: Float, private val reverseTolerance: Float) {
    var axis = SearchAxis.Tracking
        private set
    var canPull = false
        private set
    private var sign = 0
    private var peak = 0f
    fun down(pullEligible: Boolean) { axis = SearchAxis.Tracking; canPull = pullEligible; sign = 0; peak = 0f }
    fun move(dx: Float, dy: Float) {
        if (axis == SearchAxis.Cancelled) return
        if (axis == SearchAxis.Tracking) {
            if (abs(dx) >= slop && abs(dx) > abs(dy) * 1.5f) { axis = SearchAxis.Horizontal; sign = if (dx > 0) 1 else -1 }
            else if (abs(dy) >= slop && abs(dy) > abs(dx) * 1.5f) axis = SearchAxis.Vertical
        }
        if (axis == SearchAxis.Horizontal) {
            peak = max(peak, dx * sign)
            if (peak - dx * sign > reverseTolerance || abs(dy) > max(slop * 2, abs(dx) / 1.5f)) cancel()
        }
    }
    fun cancel() { axis = SearchAxis.Cancelled; canPull = false }
    fun release(dx: Float, dy: Float, elapsed: Long, velocityX: Float, lastMotionAge: Long,
                pull: Float, pullThreshold: Float, swipeDistance: Float, swipeVelocity: Float, fastEnabled: Boolean): SearchGestureAction {
        val current = axis
        val pullAllowed = canPull
        cancel() // A second release never acts, even before the next down.
        if (current == SearchAxis.Vertical && pullAllowed && pull >= pullThreshold) return SearchGestureAction.Dismiss
        if (current != SearchAxis.Horizontal || !fastEnabled || elapsed !in 1..400 || lastMotionAge > 100 || abs(dy) * 2 >= abs(dx)) return SearchGestureAction.None
        val average = dx * 1000 / elapsed
        val velocity = if (abs(velocityX) > abs(average) && velocityX * dx > 0) velocityX else average
        if (abs(dx) < swipeDistance || abs(velocity) < swipeVelocity || velocity * dx <= 0) return SearchGestureAction.None
        return if (dx > 0) SearchGestureAction.NextScope else SearchGestureAction.PreviousScope
    }
}
