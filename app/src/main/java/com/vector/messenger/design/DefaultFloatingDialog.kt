package com.vector.messenger.design

import android.graphics.drawable.ColorDrawable
import android.view.WindowManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.vector.messenger.R
import com.vector.messenger.feature.LocalAppearance

private class FloatingBackdrop { var windows by mutableIntStateOf(0) }
private val LocalFloatingBackdrop = staticCompositionLocalOf<FloatingBackdrop?> { null }
internal val LocalFloatingContent = staticCompositionLocalOf { false }

@Composable
fun floatingInterfaceOpen(): Boolean = (LocalFloatingBackdrop.current?.windows ?: 0) > 0

/** One render effect on the actual scene, never on the separate, sharp dialog window. */
@Composable
fun FloatingBackdropHost(content: @Composable () -> Unit) {
    val backdrop = remember { FloatingBackdrop() }
    val settings = LocalAppearance.current
    val radius = with(LocalDensity.current) { glassBlurRadius().toPx() }
    val available = backdropBlurAvailable()
    val animated = animateFloatAsState(if (backdrop.windows > 0 && available) radius else 0f,
        VectorMotionSpec.transition(settings), label = "Floating backdrop")
    CompositionLocalProvider(LocalFloatingBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize().graphicsLayer {
            val value = animated.value
            renderEffect = if (available && value > .01f) BlurEffect(value, value, TileMode.Clamp) else null
        }) { content() }
    }
}

@Composable
internal fun RegisterFloatingBackdrop() {
    val backdrop = LocalFloatingBackdrop.current
    DisposableEffect(backdrop) {
        if (backdrop != null) backdrop.windows++
        onDispose { if (backdrop != null) backdrop.windows-- }
    }
}

/** Native window owns focus, outside taps, Back and exit animation even if the caller removes it. */
@Composable
fun DefaultFloatingDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    RegisterFloatingBackdrop()
    val settings = LocalAppearance.current
    val colors = DefaultDesign.colors
    val available = backdropBlurAvailable()
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        ConfigureFloatingWindow()
        CompositionLocalProvider(LocalFloatingContent provides true) {
            // Wrap content, rather than a fullscreen hit target: outside taps still dismiss.
            Column(Modifier.safeDrawingPadding().imePadding().padding(24.dp)
                .widthIn(max = 440.dp).fillMaxWidth().heightIn(max = 580.dp)
                .semantics { paneTitle = title },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, color = colors.text, style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center, modifier = Modifier.semantics { heading() })
                content()
            }
        }
    }
}

/** Borderless modal using the same scene blur, dimming, focus and window motion as dialogs. */
@Composable
fun DefaultFloatingOverlay(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    RegisterFloatingBackdrop()
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = androidx.compose.ui.window.SecureFlagPolicy.SecureOn)) {
        ConfigureFloatingWindow()
        CompositionLocalProvider(LocalFloatingContent provides true) { content() }
    }
}

@Composable
private fun ConfigureFloatingWindow() {
    val settings = LocalAppearance.current
    val available = backdropBlurAvailable()
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        DisposableEffect(window, settings.reducedMotion, settings.intensity, available) {
            window?.apply {
                setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
                addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                attributes = attributes.apply {
                    dimAmount = if (available) .48f else .78f
                    windowAnimations = if (VectorMotionSpec.strength(settings) == 0f) 0 else R.style.DefaultFloatingWindowAnimation
                }
            }
            onDispose { /* The window is dismissed by Compose; no retained overlay or job. */ }
        }
 }
