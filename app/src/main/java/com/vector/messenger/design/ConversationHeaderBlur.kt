package com.vector.messenger.design

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Measured Scaffold header inset, shared only with the conversation's scroll viewport. */
val LocalConversationHeaderInset = staticCompositionLocalOf { 0.dp }

/** A single live viewport recording, smoothly mixed at three blur radii over just 55 dp.
 * These are retained drawing commands, not bitmaps or duplicate message compositions. Only the
 * narrow sampling regions are filtered. The header and composer are never part of this layer.
 */
@Composable
fun Modifier.conversationMessageDissolve(): Modifier {
    val density = LocalDensity.current
    val header = with(density) { LocalConversationHeaderInset.current.toPx() }
    val radius = with(density) { glassBlurRadius().toPx() }
    val source = rememberGraphicsLayer()
    val blurred = listOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    val parts = listOf(rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer(), rememberGraphicsLayer())
    val composite = rememberGraphicsLayer()
    return drawWithCache {
        val top = header.coerceIn(0f, size.height)
        val span = 55.dp.toPx()
        val end = (top + span).coerceAtMost(size.height)
        val height = (end - top).coerceAtLeast(0f)
        val stripSize = IntSize(size.width.roundToInt(), ceil(height.toDouble()).toInt())
        val margin = ceil((radius * 3f).toDouble()).toFloat()
        val sampleSize = IntSize(stripSize.width, ceil((height + margin * 2).toDouble()).toInt())
        fun smooth(value: Float): Float { val x = value.coerceIn(0f, 1f); return x * x * (3f - 2f * x) }
        fun opacity(d: Float): Float = when {
            d <= 15f -> .4f * smooth(d / 15f)
            d <= 30f -> .4f + .35f * smooth((d - 15f) / 15f)
            else -> .75f + .25f * smooth((d - 30f) / 25f)
        }
        // Smooth partition of unity: no radius bands or tint, and alpha reaches zero at the
        // header edge. Inside the header all messages are invisible, leaving its controls sharp.
        fun weight(level: Int, d: Float): Float {
            val segment = if (d < 15f) 0 else if (d < 30f) 1 else 2
            val t = smooth(when (segment) { 0 -> d / 15f; 1 -> (d - 15f) / 15f; else -> (d - 30f) / 25f })
            return when (level) { segment -> 1f - t; segment + 1 -> t; else -> 0f }
        }
        val masks = List(4) { level ->
            Brush.verticalGradient(*Array(57) { step ->
                val d = step * 55f / 56f
                (step / 56f) to Color.White.copy(alpha = opacity(d) * weight(level, d))
            }, endY = span)
        }
        val fade = Brush.verticalGradient(*Array(57) { step ->
            val d = step * 55f / 56f
            (step / 56f) to Color.White.copy(alpha = opacity(d))
        }, startY = top, endY = top + span)
        val fallbackBounds = Rect(0f, top, size.width, end)
        val fallbackPaint = Paint()
        val radii = floatArrayOf(radius * 20f / 24f, radius * 14f / 24f, radius * 7f / 24f)
        blurred.forEachIndexed { index, layer ->
            layer.clip = true
            layer.renderEffect = if (Build.VERSION.SDK_INT >= 31 && radius > 0f) BlurEffect(radii[index], radii[index], TileMode.Decal) else null
        }
        parts.forEach { layer ->
            layer.clip = true
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.blendMode = BlendMode.Plus
        }
        composite.clip = true
        composite.compositingStrategy = CompositingStrategy.Offscreen
        onDrawWithContent {
            val realBlur = Build.VERSION.SDK_INT >= 31 && radius > 0f && drawContext.canvas.nativeCanvas.isHardwareAccelerated
            if (stripSize.width <= 0 || stripSize.height <= 0) {
                // An IME-constrained viewport entirely behind the header has no visible content.
            } else if (!realBlur) {
                // Android 10/11, software canvas, or Blur Off: real alpha-only dissolve, never
                // a dark scrim pretending to blur. Canvas saveLayer confines the mask locally.
                clipRect(top = end) { this@onDrawWithContent.drawContent() }
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(fallbackBounds, fallbackPaint)
                    try {
                        clipRect(top = top, bottom = end) {
                            this@onDrawWithContent.drawContent()
                            drawRect(fade, blendMode = BlendMode.DstIn)
                        }
                    } finally { canvas.restore() }
                }
            } else {
                source.record { this@onDrawWithContent.drawContent() }
                clipRect(top = end) { drawLayer(source) }
                blurred.forEachIndexed { index, layer ->
                    layer.record(size = sampleSize) {
                        translate(top = margin - top) { drawLayer(source) }
                    }
                    parts[index].record(size = stripSize) {
                        translate(top = -margin) { drawLayer(layer) }
                        drawRect(masks[index], blendMode = BlendMode.DstIn)
                    }
                }
                parts[3].record(size = stripSize) {
                    translate(top = -top) { drawLayer(source) }
                    drawRect(masks[3], blendMode = BlendMode.DstIn)
                }
                composite.record(size = stripSize) { parts.forEach { drawLayer(it) } }
                clipRect(top = top, bottom = end) { translate(top = top) { drawLayer(composite) } }
            }
        }
    }
}
