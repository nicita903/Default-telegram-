package com.vector.messenger.feature

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vector.core.Delivery
import com.vector.core.Message
import com.vector.messenger.design.*
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Session-scoped consumption prevents a status transition replay after row disposal or navigation. */
internal class MessageStatusTransitions {
    private var session: Long? = null
    private val states = linkedMapOf<Pair<Long, Long>, Delivery>()
    fun record(epoch: Long, message: Message): Delivery? {
        if (session != epoch) { states.clear(); session = epoch }
        val previous = states.put(message.chatId to message.id, message.delivery)
        while (states.size > 2048) states.remove(states.keys.first())
        return previous
    }
}

/** Lines decorate the Text drawing only: no status row, inset, minimum width, or extra height. */
@Composable
internal fun MessageTimestamp(message: Message, statusVisible: Boolean, transitions: MessageStatusTransitions,
    session: Long, modifier: Modifier = Modifier) {
    val colors = DefaultDesign.colors
    if (!message.outgoing) {
        Text(message.time, modifier.widthIn(max = 112.dp), style = MaterialTheme.typography.labelSmall, color = colors.secondary)
        return
    }
    if (message.delivery == Delivery.LocalDemo) {
        Column(modifier.widthIn(max = 112.dp), horizontalAlignment = Alignment.End) {
            Text(message.time, style = MaterialTheme.typography.labelSmall, color = colors.secondary)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                DesignIcon(VectorIcons.Clock, "Stored locally; not sent to Telegram", Modifier.size(12.dp), tint = colors.accent)
                Text("Local demo", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
            }
        }
        return
    }
    val appearance = LocalAppearance.current
    val sending = message.delivery == Delivery.Sending
    val failed = message.delivery == Delivery.Failed
    val animationEnabled = VectorMotionSpec.strength(appearance) > 0f
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { started = true; onStopOrDispose { started = false } }
    val active = statusVisible && started && animationEnabled
    val currentActive by rememberUpdatedState(active)
    val currentAppearance by rememberUpdatedState(appearance)
    val first = remember(session, message.chatId, message.id) { Animatable(if (message.delivery == Delivery.Read) 1f else 0f) }
    val second = remember(session, message.chatId, message.id) { Animatable(if (message.delivery == Delivery.Read) 1f else 0f) }
    val reveal = remember(session, message.chatId, message.id) { Animatable(1f) }
    var observed by remember(session, message.chatId, message.id) { mutableStateOf(false) }
    LaunchedEffect(session, message.chatId, message.id, message.delivery, active) {
        val previous = transitions.record(session, message)
        val animate = observed && currentActive
        observed = true
        val finalColor = if (message.delivery == Delivery.Read) 1f else 0f
        first.snapTo(finalColor); second.snapTo(finalColor); reveal.snapTo(1f)
        if (animate && previous == Delivery.Sent && message.delivery == Delivery.Read) {
            first.snapTo(0f); second.snapTo(0f)
            coroutineScope {
                launch {
                    first.animateTo(1.06f, tween(VectorMotionSpec.duration(180, currentAppearance), easing = FastOutSlowInEasing))
                    first.animateTo(1f, tween(VectorMotionSpec.duration(70, currentAppearance)))
                }
                launch {
                    delay(VectorMotionSpec.duration(70, currentAppearance).toLong())
                    second.animateTo(1.06f, tween(VectorMotionSpec.duration(180, currentAppearance), easing = FastOutSlowInEasing))
                    second.animateTo(1f, tween(VectorMotionSpec.duration(70, currentAppearance)))
                }
            }
        } else if (animate && previous == Delivery.Sending && message.delivery == Delivery.Sent) {
            reveal.snapTo(0f)
            reveal.animateTo(1f, tween(VectorMotionSpec.duration(180, currentAppearance), easing = LinearOutSlowInEasing))
        }
    }
    val phase: State<Float> = if (sending && active) {
        rememberInfiniteTransition(label = "Sending status lines").animateFloat(0f, 1f,
            infiniteRepeatable(tween(VectorMotionSpec.duration(800, appearance), easing = LinearEasing)), label = "Sending opacity phase")
    } else rememberUpdatedState(0f)
    var layout by remember(message.time) { mutableStateOf<TextLayoutResult?>(null) }
    val runs = remember(message.time) {
        // Unicode decimal digits, with no decoration beneath the colon or a time suffix.
        Regex("\\p{Nd}+").findAll(message.time).take(2).map { it.range }.toList()
    }
    val readColor = Color(0xFF40BFFF)
    val failureColor = lerp(colors.secondary, MaterialTheme.colorScheme.error, .75f)
    Text(message.time, modifier.widthIn(max = 112.dp).semantics {
        contentDescription = "${message.time}, " + when (message.delivery) {
            Delivery.Read -> "Read"
            Delivery.Sent -> "Sent to Telegram"
            Delivery.Sending -> "Sending message"
            Delivery.Failed -> "Message failed to send"
            else -> ""
        }
    }.drawWithContent {
        val shift = 3.dp.toPx()

        translate(top = -shift) {
            this@drawWithContent.drawContent()
        }

        val measured = layout ?: return@drawWithContent

// Thicker status lines
        val stroke = 2.dp.toPx()
        runs.forEachIndexed { index, run ->
            if (run.last >= measured.layoutInput.text.length) return@forEachIndexed
            val boxes = run.map { measured.getBoundingBox(it) }
            val left = boxes.minOf { it.left }
            val right = boxes.maxOf { it.right }
            // Keep the entire stroke within the existing Text box and above any reaction row.
            val baseline = measured.getLineBaseline(measured.getLineForOffset(run.first))
            val y = (baseline - shift + 4.dp.toPx())
                .coerceAtMost(size.height - stroke / 2)
            if (failed) {
                if (index == 1) {
                    val x = right - stroke
                    val top = minOf(y, size.height - 4.dp.toPx())
                    drawLine(failureColor, Offset(x, top), Offset(x, top + 1.5.dp.toPx()), stroke, StrokeCap.Round)
                    drawCircle(failureColor, stroke / 2, Offset(x, top + 3.dp.toPx()))
                }
            } else {
                val progress = if (index == 0) first.value else second.value
                val color = lerp(lerp(colors.secondary, readColor, progress.coerceIn(0f, 1f)), Color.White,
                    (progress - 1f).coerceAtLeast(0f) * VectorMotionSpec.strength(appearance))
                val alpha = if (sending && active) {
                    val offset = (phase.value - index * .0875f + 1f) % 1f
                    .6f - .2f * kotlin.math.cos(offset * 2f * kotlin.math.PI.toFloat()) * VectorMotionSpec.strength(appearance)
                } else 1f
                // Inset the round caps so their painted width matches the rendered digit run.
                if (right - left > stroke && reveal.value > 0f) drawLine(color,
                    Offset(left + stroke / 2, y), Offset(left + stroke / 2 + (right - left - stroke) * reveal.value, y),
                    stroke, StrokeCap.Round, alpha = alpha)
            }
        }
    }, style = MaterialTheme.typography.labelSmall, color = colors.secondary,
        maxLines = 1, softWrap = false, onTextLayout = { if (layout != it) layout = it })
}
