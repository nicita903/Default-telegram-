package com.vector.messenger.navigation.gesture

enum class GestureNavigationAction { None, OpenModules, PreviousModule, SwitchSearch }

/** Deliberately contains no coordinates, IDs, screen titles, queries, or account information. */
data class GestureDiagnosticEvent(
    val state: VectorGestureState,
    val recognizedGesture: VectorGestureDirection?,
    val thresholdDp: Float,
    val holdMillis: Long,
    val cancellation: GestureCancelReason,
    val navigationAction: GestureNavigationAction,
)

/** One authoritative module policy for down, timeout and release, including hidden handlers. */
fun moduleGestureEligibility(enabled: Boolean, searchActive: Boolean, modalOpen: Boolean, touchExploration: Boolean): GestureCancelReason = when {
    searchActive -> GestureCancelReason.SearchActive
    modalOpen -> GestureCancelReason.ModalOpen
    touchExploration -> GestureCancelReason.TouchExploration
    !enabled -> GestureCancelReason.Disabled
    else -> GestureCancelReason.None
}
