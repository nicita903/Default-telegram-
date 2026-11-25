package com.vector.messenger.design

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.vector.core.PresenceKind
import java.time.Instant
import java.time.ZoneId
import java.util.Date
import kotlinx.coroutines.delay
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner

import androidx.compose.animation.core.*
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.Flow
import androidx.lifecycle.compose.LifecycleStartEffect
import com.vector.core.ConversationSubtitle
import com.vector.messenger.feature.LocalAppearance

/** Keep presence collection in the subtitle's own composition scope, away from the chat. */
@Composable
fun ConversationHeaderStatus(source: Flow<ConversationSubtitle>) {
    val subtitle by source.collectAsStateWithLifecycle(initialValue = ConversationSubtitle())
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Refresh date boundaries, time-zone and clock preferences while an idle header is visible.
    // This state lives only in the subtitle; it does not recompose the message list.
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) { value = Instant.now(); delay(60_000) }
        }
    }
    val text = remember(subtitle, now, configuration) { compactPresenceText(subtitle, context, now) }
    ConversationHeaderSubtitle(subtitle.copy(text = text, presence = null))
}

/** Only the exact Offline status may reveal a timestamp; approximate statuses stay approximate. */
private fun compactPresenceText(value: ConversationSubtitle, context: Context, now: Instant): String {
    if (value.activity || value.online) return value.text
    val presence = value.presence ?: return value.text.removePrefix("last seen ")
    return when (presence.kind) {
        PresenceKind.Recently -> "recently"
        PresenceKind.WithinWeek -> "within a week"
        PresenceKind.WithinMonth -> "within a month"
        PresenceKind.Offline -> {
            val timestamp = presence.wasOnline?.takeIf { it > 0 } ?: return "unavailable"
            val instant = Instant.ofEpochSecond(timestamp)
            val zone = ZoneId.systemDefault()
            val date = instant.atZone(zone).toLocalDate()
            val today = now.atZone(zone).toLocalDate()
            val time = DateFormat.getTimeFormat(context).format(Date.from(instant))
            when (date) {
                today -> time
                today.minusDays(1) -> "yesterday $time"
                else -> {
                    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or
                        (if (date.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
                    "${DateUtils.formatDateTime(context, instant.toEpochMilli(), flags)}, $time"
                }
            }
        }
        else -> value.text.removePrefix("last seen ")
    }
}

/** A fixed subtitle slot: fade status only; preserve the activity clock across text updates. */
@Composable
fun ConversationHeaderSubtitle(value: ConversationSubtitle) {
    val appearance = LocalAppearance.current
    val colors = DefaultDesign.colors
    val subtitleHeight = with(LocalDensity.current) { 16.sp.toDp() }
    var started by remember { mutableStateOf(false) }
    LifecycleStartEffect(Unit) { started = true; onStopOrDispose { started = false } }
    val active = started && VectorMotionSpec.strength(appearance) > 0
    val phase: State<Float> = if (value.activity && active) rememberInfiniteTransition(label = "Chat activity").animateFloat(
        0f, 1f, infiniteRepeatable(tween(VectorMotionSpec.duration(780, appearance), easing = LinearEasing)), label = "Activity dots")
        else rememberUpdatedState(0f)
    Crossfade(value, Modifier.fillMaxWidth().height(subtitleHeight),
        animationSpec = tween(VectorMotionSpec.duration(170, appearance)), label = "Contact status") { status ->
        Row(Modifier.fillMaxWidth().height(subtitleHeight).semantics(mergeDescendants = true) { contentDescription = status.text },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)) {
            if (status.online && !status.activity) Canvas(Modifier.size(4.dp)) { drawCircle(colors.online) }
            Text(status.text, Modifier.weight(1f, fill = false),
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                color = if (status.online && !status.activity) colors.online else colors.secondary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (status.activity) Canvas(Modifier.size(width = 15.dp, height = 3.dp)) {
                val radius = 1.5.dp.toPx()
                repeat(3) { index ->
                    val cycle = (phase.value - index * .16f + 1f) % 1f
                    val alpha = if (active) .55f - .25f * kotlin.math.cos(cycle * 2f * kotlin.math.PI.toFloat()) * VectorMotionSpec.strength(appearance) else .7f
                    drawCircle(colors.accent, radius, Offset(radius + index * 6.dp.toPx(), radius), alpha = alpha)
                }
            }
        }
    }
}
