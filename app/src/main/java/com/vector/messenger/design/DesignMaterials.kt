package com.vector.messenger.design

import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import com.vector.messenger.feature.LocalAppearance

/** Aurora's canvas: a single flat fill. The animated colour field is drawn separately. */
@Composable
fun Modifier.designCanvas(color: Color? = null): Modifier {
    val colors = DefaultDesign.colors
    return background(color ?: colors.base)
}

/** Translucent foreground material; never blurs its children or mislabels tint as backdrop blur.
 * Supported modal windows obtain real compositor backdrop blur from GlassDialogMaterial. */
@Composable
fun DefaultGlassSurface(modifier: Modifier = Modifier, shape: Shape = DefaultDesign.panelShape,
    content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glassSurface(shape), content = content)
}

/** How solid the glass fill is; the appearance panel no longer exposes this, so it is one value. */
private const val GlassSolid = .72f

/** Hairline glass over the colour field: no drop shadow, a thin lit edge. Always on. */
@Composable
fun Modifier.glassSurface(shape: Shape = DefaultDesign.panelShape): Modifier {
    val colors = DefaultDesign.colors
    if (LocalFlatSurfaces.current) return clip(shape).background(colors.panel)
    return clip(shape).drawWithCache {
        val light = Brush.linearGradient(listOf(Color.White.copy(alpha = .105f), Color.White.copy(alpha = .028f), Color.Transparent),
            start = androidx.compose.ui.geometry.Offset.Zero,
            end = androidx.compose.ui.geometry.Offset(size.width * .7f, size.height))
        val tint = Brush.radialGradient(listOf(colors.accent.copy(alpha = .07f), Color.Transparent),
            center = androidx.compose.ui.geometry.Offset(size.width * .8f, 0f), radius = size.width * .9f)
        onDrawBehind {
            drawRect(colors.panel.copy(alpha = GlassSolid * .42f))
            drawRect(light); drawRect(tint)
        }
    }.border(.75.dp,
        Brush.linearGradient(listOf(Color.White.copy(alpha = .30f), colors.accent.copy(alpha = .18f), Color.White.copy(alpha = .055f))), shape)
}

/** Small highlight for the filled primary action, without another surface or control. */
@Composable
fun Modifier.glassHighlight(): Modifier = if (LocalFlatSurfaces.current) this else drawWithCache {
    val shine = Brush.verticalGradient(listOf(Color.White.copy(alpha = .22f), Color.Transparent), endY = size.height * .65f)
    onDrawBehind { drawRect(shine) }
}

/** Shared restrained edging: a quiet glass highlight on the surface edge. */
@Composable
fun Modifier.designFrame(shape: Shape = DefaultDesign.panelShape, tint: Color? = null): Modifier {
    val colors = DefaultDesign.colors
    return border(.5.dp, tint ?: colors.text.copy(alpha = .09f), shape)
}

/** Radius steps shared by every glass blur surface; index 0 means off. */
private val blurSteps = listOf(0, 8, 16, 24)

/** Blur radius chosen by the existing "Glass blur" setting, so no surface adds a second control. */
@Composable
fun glassBlurRadius(): Dp = blurSteps[LocalAppearance.current.glassBlur.coerceIn(0, blurSteps.lastIndex)].dp

/** Pure capability rule for the backdrop blur, so the scrim fallback stays testable off-device. */
fun canBlurBackdrop(sdkInt: Int, glassBlur: Int): Boolean = sdkInt >= 31 && glassBlur > 0

/** Pure scrim rule: a sheet may only be translucent when a real blur renders behind it. */
fun backdropScrimAlpha(blurAvailable: Boolean): Float = if (blurAvailable) .62f else .92f

/** True when a real backdrop blur can render right now: API 31+ with a radius above zero. */
@Composable
fun backdropBlurAvailable(): Boolean = canBlurBackdrop(Build.VERSION.SDK_INT, LocalAppearance.current.glassBlur)

/**
 * Content blur for the screen an in-window sheet covers. Compose can only blur the composables a
 * modifier is attached to, never what sits *behind* them, so the sheet's caller blurs the content
 * while the sheet is open instead. No-op below API 31 or with glass blur off, which is why a sheet
 * takes its own alpha from [backdropScrim] rather than hardcoding one.
 */
@Composable
fun Modifier.backdropBlur(enabled: Boolean): Modifier {
    if (!enabled || !backdropBlurAvailable()) return this
    return blur(glassBlurRadius(), BlurredEdgeTreatment.Rectangle)
}

/** Scrim alpha for a sheet over that backdrop: translucent when the blur renders, opaque otherwise. */
@Composable
fun backdropScrim(): Float = backdropScrimAlpha(backdropBlurAvailable())

/** Native compositor blur for small modal windows only. System-disabled/API<31 uses tint alone.
 * No Compose content blur, screenshot capture, custom renderer or per-row offscreen pass. */
@Composable
fun GlassDialogMaterial() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    val context = LocalContext.current
    val settings = LocalAppearance.current
    val allowed = !settings.reducedMotion
    val radius = with(LocalDensity.current) { glassBlurRadius().roundToPx() }
    DisposableEffect(window, allowed, radius) {
        if (window != null && Build.VERSION.SDK_INT >= 31) {
            val manager = context.getSystemService(WindowManager::class.java)
            val enabled = allowed && radius > 0 && manager.isCrossWindowBlurEnabled
            window.attributes = window.attributes.apply { blurBehindRadius = if (enabled) radius else 0 }
            if (enabled) window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
        onDispose {
            if (window != null && Build.VERSION.SDK_INT >= 31) {
                window.attributes = window.attributes.apply { blurBehindRadius = 0 }
                window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
            }
        }
    }
}
