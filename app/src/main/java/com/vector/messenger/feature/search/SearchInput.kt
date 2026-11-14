package com.vector.messenger.feature.search

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.*
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.*
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.vector.messenger.feature.AppearanceSettings

internal enum class SearchRegionKind { Header, Results, Excluded }
internal class SearchRegions {
    private data class Region(val scope: SearchScope, val kind: SearchRegionKind, val coordinates: LayoutCoordinates)
    private val regions = mutableMapOf<Any, Region>()
    fun put(key: Any, scope: SearchScope, kind: SearchRegionKind, coordinates: LayoutCoordinates) { regions[key] = Region(scope, kind, coordinates) }
    fun remove(key: Any) { regions.remove(key) }
    fun contains(scope: SearchScope, kind: SearchRegionKind, position: Offset) = regions.values.any {
        it.scope == scope && it.kind == kind && it.coordinates.isAttached && it.coordinates.boundsInRoot().contains(position)
    }
}
internal val LocalSearchRegions = staticCompositionLocalOf { SearchRegions() }
internal val LocalSearchScope = staticCompositionLocalOf { SearchScope.Chats }
internal fun Modifier.searchRegion(kind: SearchRegionKind): Modifier = composed {
    val regions = LocalSearchRegions.current
    val scope = LocalSearchScope.current
    val key = remember { Any() }
    DisposableEffect(regions, key) { onDispose { regions.remove(key) } }
    onGloballyPositioned { regions.put(key, scope, kind, it) }
}

@Stable
internal class SearchPullMotion {
    var raw by mutableFloatStateOf(0f)
    var dragging by mutableStateOf(false)
    var confirmed = false
    var header = false
    fun reset() { raw = 0f; dragging = false; confirmed = false }
}
internal fun searchPullOffset(raw: Float, threshold: Float) = raw * .55f / (1f + raw / (threshold * 4f))
private class SearchCoordinates { var value: LayoutCoordinates? = null }

/** Exclusive Search owner: horizontal intent is claimed before the vertical list; remaining
 * vertical distance comes through nested scroll only when that list STARTED at its top. */
internal fun Modifier.searchWorkspaceInput(
    scope: SearchScope, enabled: Boolean, settings: AppearanceSettings, motion: SearchPullMotion,
    resultsAtTop: () -> Boolean, onDismiss: () -> Unit, onSwitch: (SearchGestureAction) -> Unit,
): Modifier = composed {
    val density = LocalDensity.current
    val slop = LocalViewConfiguration.current.touchSlop
    val threshold = with(density) { settings.pullDistance.dp.toPx() }
    val horizontalDistance = with(density) { (48f * 100 / settings.sensitivity).dp.toPx() }
    val horizontalVelocity = with(density) { (450f * 100 / settings.sensitivity).dp.toPx() }
    val reversal = with(density) { 14.dp.toPx() }
    val edge = with(density) { 24.dp.toPx() }
    val systemLeft = WindowInsets.systemGestures.getLeft(density, LocalLayoutDirection.current).toFloat()
    val systemRight = WindowInsets.systemGestures.getRight(density, LocalLayoutDirection.current).toFloat()
    val regions = LocalSearchRegions.current
    val coordinates = remember { SearchCoordinates() }
    val recognizer = remember(slop, reversal) { SearchGestureRecognizer(slop, reversal) }
    val haptic = LocalHapticFeedback.current
    val top by rememberUpdatedState(resultsAtTop)
    val dismiss by rememberUpdatedState(onDismiss)
    val switch by rememberUpdatedState(onSwitch)
    fun confirm() {
        if (motion.raw >= threshold && !motion.confirmed) {
            motion.confirmed = true
            if (settings.haptics) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    val nested = remember(recognizer, motion, enabled, threshold, settings.haptics, settings.dismissSearch) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || !settings.dismissSearch || source != NestedScrollSource.UserInput || motion.header || motion.raw <= 0 || available.y >= 0) return Offset.Zero
                val consumed = available.y.coerceAtLeast(-motion.raw)
                motion.raw += consumed
                return Offset(0f, consumed)
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (!enabled || !settings.dismissSearch || source != NestedScrollSource.UserInput || motion.header || recognizer.axis != SearchAxis.Vertical || !recognizer.canPull || available.y <= 0) return Offset.Zero
                motion.raw = (motion.raw + available.y).coerceAtMost(threshold * 3)
                motion.dragging = true
                confirm()
                return Offset(0f, available.y)
            }
            override suspend fun onPreFling(available: Velocity): Velocity = if (motion.raw > 0) available else Velocity.Zero
        }
    }
    onGloballyPositioned { coordinates.value = it }.nestedScroll(nested)
        .pointerInput(scope, enabled, threshold, horizontalDistance, horizontalVelocity, slop, settings.fastSearch, settings.dismissSearch, settings.haptics, systemLeft, systemRight) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val position = coordinates.value?.localToRoot(down.position) ?: down.position
                val header = regions.contains(scope, SearchRegionKind.Header, position)
                val results = regions.contains(scope, SearchRegionKind.Results, position)
                if (down.type != PointerType.Touch || down.position.x < maxOf(edge, systemLeft) || down.position.x > size.width - maxOf(edge, systemRight) ||
                    regions.contains(scope, SearchRegionKind.Excluded, position) || (!header && !results)) return@awaitEachGesture
                motion.reset(); motion.header = header
                recognizer.down(settings.dismissSearch && (header || (results && top())))
                val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                var lastMotion = down.uptimeMillis
                var committed = false
                try {
                    while (true) {
                        val initial = awaitPointerEvent(PointerEventPass.Initial)
                        val change = initial.changes.firstOrNull { it.id == down.id } ?: break
                        if (initial.changes.size != 1 || (!change.pressed && !change.changedToUp())) { recognizer.cancel(); break }
                        val delta = change.position - down.position
                        recognizer.move(delta.x, delta.y)
                        if (recognizer.axis == SearchAxis.Cancelled) break
                        if (change.positionChangedIgnoreConsumed()) lastMotion = change.uptimeMillis
                        change.historical.forEach { velocity.addPosition(it.uptimeMillis, it.position) }
                        velocity.addPosition(change.uptimeMillis, change.position)
                        val ownsHorizontal = recognizer.axis == SearchAxis.Horizontal && settings.fastSearch
                        val ownsHeader = recognizer.axis == SearchAxis.Vertical && header && recognizer.canPull
                        if (ownsHeader && change.pressed) {
                            motion.raw = delta.y.coerceIn(0f, threshold * 3)
                            motion.dragging = true
                            confirm()
                        }
                        // Do not consume the up before checking cancellation/child ownership.
                        if (change.pressed && (ownsHorizontal || ownsHeader)) change.consume()
                        val main = awaitPointerEvent(PointerEventPass.Main)
                        val after = main.changes.firstOrNull { it.id == down.id } ?: break
                        if (!after.pressed) {
                            if (after.isConsumed) { recognizer.cancel(); break }
                            val action = recognizer.release(delta.x, delta.y, after.uptimeMillis - down.uptimeMillis,
                                velocity.calculateVelocity().x, after.uptimeMillis - lastMotion, motion.raw, threshold,
                                horizontalDistance, horizontalVelocity, settings.fastSearch)
                            if (action == SearchGestureAction.Dismiss) {
                                after.consume(); committed = true; motion.dragging = false; dismiss()
                            } else if (action != SearchGestureAction.None) { after.consume(); switch(action) }
                            break
                        }
                        if (!ownsHorizontal && !ownsHeader && recognizer.axis != SearchAxis.Vertical && after.isConsumed) { recognizer.cancel(); break }
                    }
                } finally {
                    recognizer.cancel()
                    if (!committed) motion.reset()
                }
            }
        }
}
