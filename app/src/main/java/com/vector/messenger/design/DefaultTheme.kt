package com.vector.messenger.design

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.vector.core.ThemeMode

object Spacing {
    val small = 8.dp
    val medium = 16.dp
    val large = 24.dp
}
private val DarkColors = darkColorScheme(
    background = Color.Black, surface = Color(0xFF101114), surfaceContainer = Color(0xFF181A1F),
    primary = Color(0xFFA6E3D0), onPrimary = Color(0xFF00382B),
    onBackground = Color(0xFFF5F5F5), onSurface = Color(0xFFF5F5F5),
    onSurfaceVariant = Color(0xFF8B8F98), outlineVariant = Color(0xFF25272D),
)
private val LightColors = lightColorScheme(
    background = Color(0xFFFAFAFA), surface = Color.White, surfaceContainer = Color(0xFFF0F2F1),
    primary = Color(0xFF206854), onPrimary = Color.White,
    onBackground = Color(0xFF141715), onSurface = Color(0xFF141715),
    onSurfaceVariant = Color(0xFF555E59), outlineVariant = Color(0xFFD8DEDA),
)

@Composable
fun DefaultTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Dark -> true; ThemeMode.Light -> false }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = DefaultTypography, content = content)
}

private val DefaultTypography = Typography().let { base -> base.copy(
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, letterSpacing = (-.3).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = (-.15).sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
) }
