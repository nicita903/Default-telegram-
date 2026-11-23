package com.vector.messenger.design

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vector.core.ConversationSubtitle
import com.vector.messenger.feature.LocalAppearance

/** All feedback stays inside the existing one-line subtitle. Only active activity animates. */
@Composable
fun ConversationHeaderSubtitle(value: ConversationSubtitle) {
    val appearance = LocalAppearance.current
    val colors = DefaultDesign.colors
    val accent = Color(0xFF40BFFF)
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { started = true; onStopOrDispose { started = false } }
    val active = started && VectorMotionSpec.strength(appearance) > 0
    val online = remember { Animatable(if (value.online) 1f else 0f) }
    var previousOnline by remember { mutableStateOf(value.online) }
    LaunchedEffect(value.online, active) {
        val changed = value.online && !previousOnline
        previousOnline = value.online
        if (!value.online) online.snapTo(0f)
        else if (active && changed) {
            online.snapTo(0f)
            online.animateTo(1.08f, tween(VectorMotionSpec.duration(130, appearance)))
            online.animateTo(1f, tween(VectorMotionSpec.duration(70, appearance)))
        } else online.snapTo(1f)
    }
    val phase: State<Float> = if (value.activity && active) rememberInfiniteTransition(label = "Chat activity").animateFloat(
        0f, 1f, infiniteRepeatable(tween(VectorMotionSpec.duration(780, appearance), easing = LinearEasing)), label = "Activity dots")
        else rememberUpdatedState(0f)
    Row(Modifier.heightIn(min = 16.dp).semantics(mergeDescendants = true) { contentDescription = value.text },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
        if (value.online && !value.activity) Canvas(Modifier.size(5.dp)) {
            drawCircle(androidx.compose.ui.graphics.lerp(accent, Color.White, (online.value - 1f).coerceAtLeast(0f)), alpha = online.value.coerceIn(0f, 1f))
        }
        Text(value.text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, color = colors.secondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (value.activity) Canvas(Modifier.size(width = 15.dp, height = 3.dp)) {
            val radius = 1.5.dp.toPx()
            repeat(3) { index ->
                val cycle = (phase.value - index * .16f + 1f) % 1f
                val alpha = if (active) .55f - .25f * kotlin.math.cos(cycle * 2f * kotlin.math.PI.toFloat()) * VectorMotionSpec.strength(appearance) else .7f
                drawCircle(accent, radius, Offset(radius + index * 6.dp.toPx(), radius), alpha = alpha)
            }
        }
    }
}
