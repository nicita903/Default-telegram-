package com.vector.messenger.navigation.gesture

import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vector.messenger.design.DefaultDesign
import com.vector.messenger.design.DesignIcon
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.feature.LocalAppearance

/**
 * Aurora's hold cue for the existing swipe-and-hold navigation.
 *
 * This is feedback only: it reads the recognizer's published state and direction and adds no
 * pointer handling, no thresholds and no navigation of its own. The bar charges over the configured
 * hold duration, so the delay is visible instead of feeling like a missed gesture. It is drawn in
 * the unscaled host, so it never moves with the shrinking surface, and it sets no semantics because
 * it only exists while a finger is down.
 */
@Composable
fun AuroraGestureCue(
    state: VectorGestureState,
    direction: VectorGestureDirection?,
    holdMillis: Int,
    modifier: Modifier = Modifier,
) {
    if (direction == null) return
    val appearance = LocalAppearance.current
    val strength = VectorMotionSpec.strength(appearance)
    val live = state == VectorGestureState.ThresholdReached ||
        state == VectorGestureState.Holding || state == VectorGestureState.Activated
    val charge by animateFloatAsState(
        targetValue = if (live) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (live) holdMillis.coerceIn(120, 400) else 110,
            easing = if (live) LinearEasing else FastOutLinearInEasing,
        ),
        label = "Aurora hold charge",
    )
    if (!live && charge <= .01f) return
    val right = direction == VectorGestureDirection.Right
    val colors = DefaultDesign.colors
    Box(modifier.fillMaxSize().clearAndSetSemantics {}) {
        Box(
            Modifier.align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .fillMaxHeight().width(2.dp)
                .background(Brush.verticalGradient(listOf(
                    Color.Transparent,
                    colors.accent.copy(alpha = .55f * charge * strength),
                    Color.Transparent,
                ))),
        )
        Column(
            modifier = Modifier
                .align(if (right) Alignment.CenterEnd else Alignment.CenterStart)
                .padding(horizontal = 12.dp)
                .graphicsLayer {
                    alpha = charge * strength
                    val scale = .82f + .18f * charge
                    scaleX = scale; scaleY = scale
                    translationX = (if (right) 1f else -1f) * (1f - charge) * 14.dp.toPx()
                },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape)
                    .background(colors.panel.copy(alpha = .66f))
                    .border(.75.dp, colors.accent.copy(alpha = .38f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                DesignIcon(
                    imageVector = if (right) Icons.Default.Menu else Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                    tint = colors.text,
                )
            }
            Text(
                text = if (right) "Menu" else "Back",
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.4.sp, fontWeight = FontWeight.Medium),
                color = colors.secondary,
            )
            Box(
                Modifier.width(26.dp).height(2.dp).clip(RoundedCornerShape(2.dp))
                    .background(colors.line.copy(alpha = .55f)),
            ) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(charge).background(colors.accent))
            }
        }
    }
}
