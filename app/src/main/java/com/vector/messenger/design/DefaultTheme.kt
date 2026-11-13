package com.vector.messenger.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vector.core.ThemeMode
import com.vector.messenger.feature.LocalAppearance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

object Spacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}
@Immutable
data class DefaultColors(
    val base: Color, val panel: Color, val raised: Color, val accent: Color,
    val text: Color, val secondary: Color, val line: Color, val outgoing: Color, val canvas: Color,
)
private val Night = DefaultColors(
    Color.Black, Color(0xFF0B0D11), Color(0xFF151921), Color(0xFF469CFF),
    Color(0xFFF2F4F7), Color(0xFF858C98), Color(0xFF1E232B), Color(0xFF10243B), Color.Black,
)
private val Day = DefaultColors(
    Color(0xFFF5F7FA), Color(0xFFEBF0F6), Color(0xFFDCE5F0), Color(0xFF155CA4),
    Color(0xFF121D2A), Color(0xFF506176), Color(0xFFC4D0DD), Color(0xFFDFECFC), Color(0xFFF5F7FA),
)
private val LocalDefaultColors = staticCompositionLocalOf { Night }
object DefaultDesign {
    val colors: DefaultColors @Composable get() = LocalDefaultColors.current
    val controlRadius = 24.dp
    val panelRadius = 24.dp
    val horizontalPadding = 20.dp
    val minimumTarget = 48.dp
}
private val DarkColors = darkColorScheme(
    background = Night.base, surface = Night.panel, surfaceContainer = Night.raised,
    primary = Night.accent, onPrimary = Color(0xFF07182B),
    onBackground = Night.text, onSurface = Night.text,
    onSurfaceVariant = Night.secondary, outlineVariant = Night.line,
)
private val LightColors = lightColorScheme(
    background = Day.base, surface = Day.panel, surfaceContainer = Day.raised,
    primary = Day.accent, onPrimary = Color.White,
    onBackground = Day.text, onSurface = Day.text,
    onSurfaceVariant = Day.secondary, outlineVariant = Day.line,
)

@Composable
fun DefaultTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    val appearance = LocalAppearance.current
    val density = LocalDensity.current
    val accent = listOf(Color(0xFF469CFF), Color(0xFF79B8FF), Color(0xFF8AA9FF))[appearance.accent]
    val colors = (if (dark) Night else Day).copy(accent = if (dark) accent else listOf(Color(0xFF155CA4), Color(0xFF22558A), Color(0xFF3D5096))[appearance.accent])
    CompositionLocalProvider(LocalDefaultColors provides colors, LocalDensity provides Density(density.density, density.fontScale * appearance.typeScale / 100f)) {
        MaterialTheme(colorScheme = (if (dark) DarkColors else LightColors).copy(primary = colors.accent), typography = DefaultTypography, content = content)
    }
}

private val DefaultTypography = Typography().let { base -> base.copy(
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp, letterSpacing = (-.5).sp),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 20.sp, letterSpacing = (-.5).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = (-.2).sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
) }
