package com.vector.messenger.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** Small code-native line icons; no additional icon dependency. */
object VectorIcons {
    val Plus by lazy { icon("Plus") { moveTo(5f, 12f); lineTo(19f, 12f); moveTo(12f, 5f); lineTo(12f, 19f) } }
    val Check by lazy { icon("Confirm") { moveTo(5f, 12f); lineTo(10f, 17f); lineTo(20f, 6f) } }
    val Send by lazy { icon("Send") { moveTo(3f, 4f); lineTo(22f, 12f); lineTo(3f, 20f); lineTo(6f, 12f); close(); moveTo(6f, 12f); lineTo(22f, 12f) } }
    val VideoCamera by lazy { icon("Video camera") { moveTo(4f, 5f); lineTo(14f, 5f); quadTo(16f, 5f, 16f, 7f); lineTo(16f, 17f); quadTo(16f, 19f, 14f, 19f); lineTo(4f, 19f); quadTo(2f, 19f, 2f, 17f); lineTo(2f, 7f); quadTo(2f, 5f, 4f, 5f); close(); moveTo(16f, 9f); lineTo(22f, 6f); lineTo(22f, 18f); lineTo(16f, 15f) } }

    val Views by lazy { icon("Views") {
        moveTo(2f, 12f); curveTo(7f, 3f, 17f, 3f, 22f, 12f); curveTo(17f, 21f, 7f, 21f, 2f, 12f); close()
        moveTo(15f, 12f); curveTo(15f, 16f, 9f, 16f, 9f, 12f); curveTo(9f, 8f, 15f, 8f, 15f, 12f); close()
    } }
    val Heart by lazy { icon("Reactions") {
        moveTo(12f, 21f); lineTo(3f, 12f); curveTo(-2f, 5f, 7f, -1f, 12f, 6f)
        curveTo(17f, -1f, 26f, 5f, 21f, 12f); lineTo(12f, 21f); close()
    } }
    val Refresh by lazy { icon("Refresh") {
        moveTo(20f, 9f); curveTo(17f, 1f, 4f, 2f, 3f, 11f); curveTo(2f, 21f, 17f, 25f, 21f, 15f)
        moveTo(20f, 3f); lineTo(20f, 9f); lineTo(14f, 9f)
    } }
    val Emoji by lazy { icon("Emoji") {
        moveTo(21f, 12f); curveTo(21f, 17f, 17f, 21f, 12f, 21f); curveTo(7f, 21f, 3f, 17f, 3f, 12f)
        curveTo(3f, 7f, 7f, 3f, 12f, 3f); curveTo(17f, 3f, 21f, 7f, 21f, 12f); close()
        moveTo(8f, 9f); lineTo(8f, 10f); moveTo(16f, 9f); lineTo(16f, 10f)
        moveTo(8f, 14f); curveTo(10f, 17f, 14f, 17f, 16f, 14f)
    } }
    val Keyboard by lazy { icon("Keyboard") {
        moveTo(4f, 5f); lineTo(20f, 5f); lineTo(22f, 7f); lineTo(22f, 17f); lineTo(20f, 19f)
        lineTo(4f, 19f); lineTo(2f, 17f); lineTo(2f, 7f); close()
        moveTo(6f, 9f); lineTo(6.1f, 9f); moveTo(10f, 9f); lineTo(10.1f, 9f)
        moveTo(14f, 9f); lineTo(14.1f, 9f); moveTo(18f, 9f); lineTo(18.1f, 9f)
        moveTo(6f, 12f); lineTo(6.1f, 12f); moveTo(10f, 12f); lineTo(10.1f, 12f)
        moveTo(14f, 12f); lineTo(14.1f, 12f); moveTo(18f, 12f); lineTo(18.1f, 12f)
        moveTo(8f, 16f); lineTo(16f, 16f)
    } }
    val Microphone by lazy { icon("Microphone") { moveTo(9f, 11f); lineTo(9f, 5f); curveTo(9f, 1f, 15f, 1f, 15f, 5f); lineTo(15f, 11f); curveTo(15f, 15f, 9f, 15f, 9f, 11f); moveTo(6f, 10f); curveTo(6f, 20f, 18f, 20f, 18f, 10f); moveTo(12f, 18f); lineTo(12f, 22f); moveTo(8f, 22f); lineTo(16f, 22f) } }
    val Minus by lazy { icon("Decrease") { moveTo(6f, 12f); lineTo(18f, 12f) } }
    private fun icon(name: String, block: PathBuilder.() -> Unit) = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.7f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block)
    }.build()
    val Chat by lazy { icon("Chat") { moveTo(5f, 4f); lineTo(19f, 4f); lineTo(21f, 6f); lineTo(21f, 16f); lineTo(19f, 18f); lineTo(9f, 18f); lineTo(3f, 22f); lineTo(3f, 6f); close() } }
    val Music by lazy { icon("Music") { moveTo(10f, 17f); lineTo(10f, 5f); lineTo(20f, 3f); lineTo(20f, 15f); moveTo(10f, 8f); lineTo(20f, 6f); moveTo(10f, 17f); curveTo(10f, 22f, 3f, 22f, 3f, 18f); curveTo(3f, 15f, 10f, 14f, 10f, 17f); moveTo(20f, 15f); curveTo(20f, 20f, 13f, 20f, 13f, 16f); curveTo(13f, 13f, 20f, 12f, 20f, 15f) } }
    val Download by lazy { icon("Download") { moveTo(12f, 3f); lineTo(12f, 16f); moveTo(7f, 11f); lineTo(12f, 16f); lineTo(17f, 11f); moveTo(4f, 17f); lineTo(4f, 21f); lineTo(20f, 21f); lineTo(20f, 17f) } }
    val Pin by lazy { icon("Pin") { moveTo(8f, 3f); lineTo(16f, 3f); moveTo(9f, 3f); lineTo(9f, 10f); lineTo(6f, 14f); lineTo(18f, 14f); lineTo(15f, 10f); lineTo(15f, 3f); moveTo(12f, 14f); lineTo(12f, 22f) } }
    val Attach by lazy { icon("Attach") { moveTo(8f, 12f); lineTo(14f, 6f); curveTo(18f, 2f, 23f, 7f, 19f, 11f); lineTo(10f, 20f); curveTo(5f, 25f, -1f, 18f, 4f, 13f); lineTo(13f, 4f); moveTo(7f, 15f); lineTo(15f, 7f) } }
    val Clock by lazy { icon("Local message") { moveTo(21f, 12f); curveTo(21f, 24f, 3f, 24f, 3f, 12f); curveTo(3f, 0f, 21f, 0f, 21f, 12f); moveTo(12f, 7f); lineTo(12f, 12f); lineTo(16f, 14f) } }
}
