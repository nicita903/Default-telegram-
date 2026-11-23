package com.vector.messenger.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.vector.messenger.feature.LocalAppearance

/** Drift period in milliseconds. Longer than any reading task, so the field never competes. */
private const val DriftMillis = 26_000
/** Travel of each field, in dp, across one drift half-cycle. */
private val DriftTravel = 54.dp

/**
 * Aurora's colour field: three soft lights under the interface.
 *
 * The static gradients are drawn once and cached. Drift is a graphics-layer transform, so a moving
 * field never re-runs composition and never re-records its brush: only the layer matrix changes.
 * Reduced motion, disabled animation or zero intensity freezes the same three lights instead of
 * falling back to a flat sheet, so the design cannot degrade into plain black.
 */
@Composable
fun AuroraField(modifier: Modifier = Modifier) {
    val appearance = LocalAppearance.current
    var active by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.lifecycle.compose.LifecycleStartEffect(Unit) { active = true; onStopOrDispose { active = false } }
    val moving = active && !appearance.reducedMotion && appearance.intensity > 0
    val drift = if (moving) rememberInfiniteTransition(label = "Aurora field").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(DriftMillis, easing = LinearEasing), RepeatMode.Reverse),
        label = "Aurora drift",
    ) else null
    val colors = DefaultDesign.colors
    Box(modifier.clipToBounds()) {
        AuroraBlob(colors.accent, Alignment.TopStart, 560.dp, (-150).dp, (-230).dp, drift, travelX = 1f, travelY = .55f, alpha = .52f)
        AuroraBlob(colors.secondaryAccent, Alignment.TopEnd, 470.dp, 120.dp, (-70).dp, drift, travelX = -.8f, travelY = .7f, alpha = .34f)
        AuroraBlob(colors.active, Alignment.CenterEnd, 520.dp, 200.dp, 120.dp, drift, travelX = -1.2f, travelY = -.5f, alpha = .22f)
        // Readability scrim: strongest behind the header, fading into the canvas by two thirds.
        Box(Modifier.matchParentSize().background(Brush.verticalGradient(
            0f to colors.base.copy(alpha = .22f),
            .40f to colors.base.copy(alpha = .80f),
            .78f to colors.base,
            1f to colors.base,
        )))
    }
}

@Composable
private fun BoxScope.AuroraBlob(
    color: Color, alignment: Alignment, size: Dp, dx: Dp, dy: Dp,
    drift: State<Float>?, travelX: Float, travelY: Float, alpha: Float,
) {
    val travel = with(LocalDensity.current) { DriftTravel.toPx() }
    Box(
        Modifier.align(alignment).offset(x = dx, y = dy).size(size)
            .graphicsLayer {
                val t = (drift?.value ?: .5f) - .5f
                translationX = travel * t * 2f * travelX
                translationY = travel * t * 2f * travelY
            }
            .background(Brush.radialGradient(listOf(
                color.copy(alpha = alpha),
                color.copy(alpha = alpha * .42f),
                Color.Transparent,
            ))),
    )
}
