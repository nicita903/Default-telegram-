package com.vector.messenger.feature.video

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.vector.path

@Composable
internal fun VideoNotePlayGlyph(paused: Boolean = true) {
    Canvas(Modifier.size(42.dp).background(Color.Black.copy(alpha = .34f), CircleShape).padding(13.dp)) {
        if (paused) drawPath(Path().apply {
            moveTo(size.width * .15f, 0f); lineTo(size.width, size.height / 2); lineTo(size.width * .15f, size.height); close()
        }, Color.White.copy(alpha = .94f))
        else {
            drawRoundRect(Color.White, Offset(1f, 0f), Size(size.width * .3f, size.height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
            drawRoundRect(Color.White, Offset(size.width * .65f, 0f), Size(size.width * .3f, size.height), androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
internal fun VideoNoteDuration(seconds: Int, modifier: Modifier = Modifier) {
    Row(modifier.background(Color.Black.copy(alpha = .7f), CircleShape).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(12.dp)) {
            drawRoundRect(Color.White, Offset(0f, size.height * .2f), Size(size.width * .65f, size.height * .6f), androidx.compose.ui.geometry.CornerRadius(1.5.dp.toPx()))
            drawPath(Path().apply { moveTo(size.width * .7f, size.height * .36f); lineTo(size.width, size.height * .18f)
                lineTo(size.width, size.height * .82f); lineTo(size.width * .7f, size.height * .64f); close() }, Color.White)
        }
        Text(videoNoteTime(seconds * 1000L), color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}
internal fun videoNoteTime(millis: Long) = "%d:%02d".format(java.util.Locale.ROOT, millis.coerceAtLeast(0) / 60000, millis.coerceAtLeast(0) / 1000 % 60)

internal object VideoNoteIcons {
    val Muted by lazy { speaker(true) }
    val Audible by lazy { speaker(false) }
    private fun speaker(muted: Boolean) = androidx.compose.ui.graphics.vector.ImageVector.Builder(
        if (muted) "Muted video" else "Video audio", 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = androidx.compose.ui.graphics.SolidColor(Color.Black),
            strokeLineWidth = 1.7f, strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
            strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round) {
            moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
            if (muted) { moveTo(17f, 9f); lineTo(22f, 15f); moveTo(22f, 9f); lineTo(17f, 15f) }
            else { moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
                moveTo(19f, 5f); curveTo(25f, 9f, 25f, 15f, 19f, 19f) }
        }
    }.build()
}
