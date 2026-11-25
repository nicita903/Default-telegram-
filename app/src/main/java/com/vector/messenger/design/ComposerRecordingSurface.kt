package com.vector.messenger.design

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.vector.messenger.feature.LocalAppearance

/** Observes presses without consuming or replacing the recorder's hold/slide gestures. */
@Composable
internal fun Modifier.composerRecordingSurface(enabled: Boolean): Modifier {
    val colors = DefaultDesign.colors
    val settings = LocalAppearance.current
    var pressed by remember { mutableStateOf(false) }
    val scale = animateFloatAsState(if (pressed) VectorMotionSpec.pressScale(.96f, settings) else 1f,
        VectorMotionSpec.press(pressed, settings), label = "Recorder press")
    val fill = animateColorAsState(colors.text.copy(alpha = if (pressed) .14f else if (settings.amoledBlack) .09f else .065f),
        VectorMotionSpec.feedback(settings), label = "Recorder surface")
    return this.graphicsLayer { scaleX = scale.value; scaleY = scale.value }
        .drawBehind { drawCircle(fill.value, radius = 18.dp.toPx()) }
        .pointerInput(enabled) {
            if (!enabled) return@pointerInput
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                try {
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                    } while (event.changes.any { it.pressed })
                } finally { pressed = false }
            }
        }
}
