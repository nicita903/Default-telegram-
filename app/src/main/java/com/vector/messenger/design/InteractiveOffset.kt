package com.vector.messenger.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.runtime.*
import com.vector.messenger.feature.LocalAppearance
import kotlinx.coroutines.flow.collectLatest

/** Pointer state is observed outside composition. Positive/interactive targets snap to the
 * finger; only a released/cancelled zero target springs back. Removal cancels the collector
 * and its animation through LaunchedEffect's scope. Read value only in a graphics layer. */
@Composable
fun rememberInteractiveOffset(
    target: () -> Float, interactive: () -> Boolean, enabled: Boolean = true,
): Animatable<Float, AnimationVector1D> {
    val animation = remember { Animatable(0f) }
    val latestTarget by rememberUpdatedState(target)
    val latestInteractive by rememberUpdatedState(interactive)
    val settings = LocalAppearance.current
    LaunchedEffect(enabled, settings.reducedMotion, settings.intensity, settings.speed) {
        if (!enabled || VectorMotionSpec.strength(settings) == 0f) { animation.snapTo(0f); return@LaunchedEffect }
        snapshotFlow { latestTarget() to latestInteractive() }.collectLatest { (value, dragging) ->
            if (dragging || value != 0f) animation.snapTo(value)
            else animation.animateTo(0f, VectorMotionSpec.settle(settings))
        }
    }
    return animation
}
