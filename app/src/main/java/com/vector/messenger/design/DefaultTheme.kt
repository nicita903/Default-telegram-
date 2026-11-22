package com.vector.messenger.design

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.vector.messenger.feature.LocalAppearance

object Spacing { val small = 8.dp; val medium = 16.dp; val large = 24.dp }

@Immutable
data class DefaultColors(
    val base: Color, val panel: Color, val raised: Color, val accent: Color,
    val text: Color, val secondary: Color, val line: Color, val outgoing: Color, val canvas: Color,
    val active: Color = accent, val secondaryAccent: Color = secondary,
    val important: Color = Color(0xFFFFAA00), val onAccent: Color = Color(0xFF07182B),
    val mediaCanvas: Color = Color.Black, val mediaChrome: Color = panel,
    val scrim: Color = Color.Black.copy(alpha = .5f),
    val pressedAccent: Color = accent, val grid: Color = Color(0xFF06202D),
    val online: Color = Color(0xFF00E65C),
)

// Aurora: a near-black canvas lit by three slow colour fields; surfaces are hairline glass, never cards.
private val Aurora = DefaultColors(
    base = Color(0xFF070910), panel = Color(0xFF0E1220), raised = Color(0xFF161C2E),
    accent = Color(0xFF7C8CFF), text = Color(0xFFF0F3FF), secondary = Color(0xFF8A93AC),
    line = Color(0xFF2A3150), outgoing = Color(0xFF1B2A52), canvas = Color.Transparent,
    active = Color(0xFFA6B0FF), secondaryAccent = Color(0xFF48E3C0),
    onAccent = Color(0xFF06121A), mediaCanvas = Color(0xFF04060C), mediaChrome = Color(0xFF0B0F1A),
    scrim = Color.Black.copy(alpha = .62f), grid = Color(0xFF1A2240), online = Color(0xFF48E3C0),
)
private val LocalDefaultColors = staticCompositionLocalOf { Aurora }
internal val LocalFlatSurfaces = staticCompositionLocalOf { false }

/** Scoped to the music workspace; playback, artwork and the account accent are unchanged. */
@Composable
fun MusicPalette(enabled: Boolean = true, content: @Composable () -> Unit) {
    if (!enabled) { content(); return }
    val parent = DefaultDesign.colors
    val amoled = LocalAppearance.current.amoledBlack
    val colors = remember(parent, amoled) { parent.copy(
        base = if (amoled) Color.Black else Color(0xFF252525),
        panel = if (amoled) Color.Black else Color(0xFF303030),
        raised = if (amoled) Color.Black else Color(0xFF393939),
        text = Color(0xFFF0F0F0), secondary = Color(0xFFA8A8A8),
        line = Color(0xFF474747), canvas = if (amoled) Color.Black else Color(0xFF252525),
    ) }
    val scheme = MaterialTheme.colorScheme.copy(background = colors.base, surface = colors.panel,
        surfaceVariant = colors.raised, surfaceContainer = colors.raised,
        onBackground = colors.text, onSurface = colors.text, onSurfaceVariant = colors.secondary)
    CompositionLocalProvider(LocalDefaultColors provides colors, LocalFlatSurfaces provides true) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}

object DefaultDesign {
    val colors: DefaultColors @Composable get() = LocalDefaultColors.current
    val controlShape: Shape get() = CircleShape
    val panelShape: Shape get() = RoundedCornerShape(24.dp)
    val inputShape: Shape get() = RoundedCornerShape(28.dp)
    val menuShape: Shape get() = RoundedCornerShape(28.dp)
    val selectionShape: Shape get() = RoundedCornerShape(20.dp)
    val avatarShape: Shape get() = CircleShape
    val composerShape: Shape get() = RoundedCornerShape(28.dp)
    val horizontalPadding = 20.dp
    val minimumTarget = 48.dp
    fun messageShape(outgoing: Boolean, grouped: Boolean): Shape = RoundedCornerShape(
        topStart = 19.dp, topEnd = 19.dp,
        bottomEnd = if (outgoing && !grouped) 7.dp else 19.dp,
        bottomStart = if (!outgoing && !grouped) 7.dp else 19.dp,
    )
}

/** The accent index selects the field's primary and secondary light; there is no light palette.
 * AMOLED keeps a pure black base while the transparent canvas keeps the colour field alive. */
private val AuroraAccents = listOf(Color(0xFF7C8CFF), Color(0xFFA78BFA), Color(0xFF48E3C0))
private val AuroraSecondary = listOf(Color(0xFF48E3C0), Color(0xFFF0A6CA), Color(0xFF7C8CFF))

internal fun auroraColors(accentIndex: Int, amoled: Boolean = false): DefaultColors {
    val index = accentIndex.coerceIn(0, 2)
    val accent = AuroraAccents[index]
    val palette = Aurora.copy(accent = accent, active = accent, secondaryAccent = AuroraSecondary[index],
        pressedAccent = lerp(accent, Color.Black, .16f))
    return if (amoled) palette.copy(base = Color.Black, panel = Color.Black,
        mediaCanvas = Color.Black, mediaChrome = Color.Black) else palette
}

@Composable
fun DefaultTheme(content: @Composable () -> Unit) {
    val appearance = LocalAppearance.current
    val density = LocalDensity.current
    val colors = remember(appearance.accent, appearance.amoledBlack) {
        auroraColors(appearance.accent, appearance.amoledBlack)
    }
    val scheme = remember(colors) {
        darkColorScheme().copy(
            // The root canvas stays transparent so the Aurora field is visible through every screen.
            background = Color.Transparent,
            surface = colors.panel, surfaceContainer = colors.raised,
            surfaceContainerLow = colors.panel, surfaceContainerHigh = colors.raised,
            surfaceContainerLowest = colors.base, surfaceContainerHighest = colors.raised,
            surfaceVariant = colors.raised, surfaceDim = colors.base, surfaceBright = colors.raised,
            primary = colors.accent, onPrimary = colors.onAccent,
            primaryContainer = colors.outgoing, onPrimaryContainer = colors.text,
            secondary = colors.secondaryAccent, onSecondary = colors.base,
            secondaryContainer = colors.raised, onSecondaryContainer = colors.text,
            tertiary = colors.important, onTertiary = colors.base,
            onBackground = colors.text, onSurface = colors.text, onSurfaceVariant = colors.secondary,
            outline = colors.secondaryAccent, outlineVariant = colors.line,
            surfaceTint = colors.accent, inverseSurface = colors.text, inverseOnSurface = colors.base,
            inversePrimary = colors.accent, scrim = colors.scrim,
        )
    }
    CompositionLocalProvider(LocalDefaultColors provides colors,
        LocalDensity provides Density(density.density, density.fontScale * appearance.typeScale / 100f)) {
        MaterialTheme(colorScheme = scheme, typography = AuroraTypography, shapes = AuroraShapes) {
            FloatingBackdropHost {
            Box(Modifier.fillMaxSize().designCanvas()) {
                AuroraField(Modifier.matchParentSize())
                content()
            }
            }
        }
    }
}

private val AuroraTypography = Typography().let { base -> base.copy(
    headlineSmall = base.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontSize = 21.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    titleMedium = base.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 23.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    labelSmall = base.labelSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
) }
private val AuroraShapes = Shapes(extraSmall = RoundedCornerShape(10.dp), small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp))
