package com.vector.messenger.navigation.gesture

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Release-only decision shared with the hold recognizer. All dimensions are physical pixels. */
fun isFastSearchSwipe(dx: Float, dy: Float, elapsed: Long, velocityX: Float, distance: Float, velocity: Float): Boolean =
    elapsed in 1..350 && abs(dx) >= distance && abs(dx) > abs(dy) * 2 && abs(velocityX) >= velocity && dx * velocityX > 0

@Stable
class PullSearchState {
    var distance by mutableFloatStateOf(0f)
        internal set
    var eligible = false
    var confirmed = false
    var released = false
}

/** Only consumes the unconsumed downward portion of a touch scroll that STARTED at the top. */
fun Modifier.pullToSearch(
    list: LazyListState, state: PullSearchState, enabled: Boolean, thresholdDp: Int,
    haptics: Boolean, onOpen: () -> Unit,
): Modifier = composed {
    val threshold = with(LocalDensity.current) { thresholdDp.dp.toPx() }
    val haptic = LocalHapticFeedback.current
    val open by rememberUpdatedState(onOpen)
    val connection = remember(list, state, threshold, enabled, haptics) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput || state.distance <= 0 || available.y >= 0) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-state.distance)
                state.distance += consumed
                return Offset(0f, consumed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || !state.eligible || source != NestedScrollSource.UserInput || available.y <= 0 || list.canScrollBackward) return Offset.Zero
                state.distance = (state.distance + available.y).coerceAtMost(threshold * 2)
                if (!state.confirmed && state.distance >= threshold) {
                    state.confirmed = true
                    if (haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                }
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity {
                val pulled = state.distance > 0
                val activate = enabled && state.eligible && state.released && state.distance >= threshold
                state.distance = 0f; state.eligible = false; state.confirmed = false; state.released = false
                if (activate) open()
                return if (pulled) available else Velocity.Zero
            }
        }
    }
    nestedScroll(connection).pointerInput(enabled, threshold) {
        if (!enabled) return@pointerInput
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            state.eligible = down.type == PointerType.Touch && !list.canScrollBackward
            state.distance = 0f; state.confirmed = false; state.released = false
            var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.size != 1) { state.eligible = false; state.distance = 0f }
                    if (event.changes.none { it.pressed }) {
                        // Compose cancellation synthesizes consumed up changes. A fling alone
                        // is never authorization to navigate; require a genuine release first.
                        state.released = event.changes.size == 1 && event.changes.single().changedToUp()
                        if (!state.released) { state.eligible = false; state.distance = 0f; state.confirmed = false }
                        released = true
                        break
                    }
                }
            } finally {
                if (!released) { state.eligible = false; state.distance = 0f; state.confirmed = false; state.released = false }
            }
        }
    }
}
