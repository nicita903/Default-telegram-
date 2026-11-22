package com.vector.messenger.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.*
import androidx.compose.ui.unit.dp

/** Small code-native line icons; no additional icon dependency. */
object VectorIcons {
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
