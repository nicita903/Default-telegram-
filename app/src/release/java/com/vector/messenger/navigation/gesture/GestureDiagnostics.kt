package com.vector.messenger.navigation.gesture

/** Release has no logger, event allocation, observer, or runtime enable switch. */
object GestureDiagnostics {
    @Suppress("UNUSED_PARAMETER")
    fun record(state: VectorGestureState, direction: VectorGestureDirection?, thresholdDp: Float,
               holdMillis: Long, reason: GestureCancelReason = GestureCancelReason.None,
               action: GestureNavigationAction = GestureNavigationAction.None) = Unit
}
