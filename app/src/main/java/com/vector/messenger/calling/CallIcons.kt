package com.vector.messenger.calling

import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** Call-only extensions of Default's existing code-native line icon style. */
internal object CallIcons {
    val MicrophoneOff by lazy { icon("Microphone off") {
        moveTo(9f, 7f); lineTo(9f, 5f); curveTo(9f, 1f, 15f, 1f, 15f, 5f); lineTo(15f, 11f)
        moveTo(6f, 10f); curveTo(6f, 20f, 18f, 20f, 18f, 10f)
        moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f)
        moveTo(3f, 3f); lineTo(21f, 21f)
    } }
    val Speaker by lazy { icon("Speaker") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 8f); curveTo(19f, 10f, 19f, 14f, 16f, 16f)
        moveTo(19f, 5f); curveTo(25f, 9f, 25f, 15f, 19f, 19f)
    } }
    val SpeakerOff by lazy { icon("Speaker off") {
        moveTo(3f, 9f); lineTo(7f, 9f); lineTo(12f, 5f); lineTo(12f, 19f); lineTo(7f, 15f); lineTo(3f, 15f); close()
        moveTo(16f, 9f); lineTo(22f, 15f); moveTo(22f, 9f); lineTo(16f, 15f)
    } }
    val Bluetooth by lazy { icon("Bluetooth") {
        moveTo(12f, 2f); lineTo(19f, 7f); lineTo(5f, 17f)
        moveTo(5f, 7f); lineTo(19f, 17f); lineTo(12f, 22f); close()
    } }
    val Phone by lazy { icon("Accept call") {
        moveTo(5f, 3f); lineTo(9f, 3f); lineTo(11f, 8f); lineTo(8f, 10f)
        curveTo(9f, 13f, 11f, 15f, 14f, 16f); lineTo(16f, 13f); lineTo(21f, 15f)
        lineTo(21f, 19f); curveTo(21f, 22f, 16f, 22f, 11f, 19f)
        curveTo(5f, 16f, 2f, 10f, 2f, 6f); curveTo(2f, 4f, 3f, 3f, 5f, 3f); close()
    } }
    val HangUp by lazy { icon("End call") {
        moveTo(2f, 12f); curveTo(6f, 6f, 18f, 6f, 22f, 12f)
        lineTo(20f, 17f); lineTo(15f, 15f); lineTo(15f, 11f)
        curveTo(13f, 10f, 11f, 10f, 9f, 11f); lineTo(9f, 15f); lineTo(4f, 17f); close()
    } }
    private fun icon(name: String, content: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = content)
    }.build()
}
