package com.vector.messenger.design

import androidx.compose.animation.core.*
import androidx.compose.animation.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.AppearanceSettings

/** Compose animations inherit Android's animator duration scale, including disabled animation. */
object VectorMotionSpec {
    const val Duration = 180
    const val ActivatedScale = .95f
    const val ControlPress = .96f
    const val ActionPress = .97f
    const val RowPress = .994f
    fun strength(settings: AppearanceSettings) = if (settings.reducedMotion) 0f else settings.intensity / 100f
    fun duration(base: Int, settings: AppearanceSettings) = if (strength(settings) == 0f) 0 else base * 100 / settings.speed
    fun pressScale(target: Float, settings: AppearanceSettings) = 1f - (1f - target) * strength(settings)
    @Composable fun pressScale(target: Float) = pressScale(target, LocalAppearance.current)
    fun surfaceScale(activated: Boolean, settings: AppearanceSettings) = if (activated) 1f - settings.zoom / 100f * strength(settings) else 1f
    fun settle(settings: AppearanceSettings): AnimationSpec<Float> {
        val speed = settings.speed / 100f
        return if (strength(settings) == 0f) snap() else spring(dampingRatio = 1f, stiffness = Spring.StiffnessMedium * speed * speed)
    }
    @Composable fun scale(): AnimationSpec<Float> = settle(LocalAppearance.current)
    // Reply's detector follows the translated bubble. Preserve its original follow physics;
    // tuning the other surface/press roles must not alter this interactive gesture's travel.
    fun replyFollow(settings: AppearanceSettings): AnimationSpec<Float> =
        if (strength(settings) == 0f) snap() else spring(dampingRatio = .9f, stiffness = Spring.StiffnessMedium * settings.speed / 100f)
    fun press(pressed: Boolean, settings: AppearanceSettings): AnimationSpec<Float> =
        if (pressed) tween(duration(70, settings), easing = LinearOutSlowInEasing) else settle(settings)
    fun <T> feedback(settings: AppearanceSettings): FiniteAnimationSpec<T> = tween(duration(100, settings), easing = LinearOutSlowInEasing)
    fun <T> transition(settings: AppearanceSettings = AppearanceSettings()): FiniteAnimationSpec<T> = tween(duration(Duration, settings), easing = FastOutSlowInEasing)
    fun <T> exit(settings: AppearanceSettings): FiniteAnimationSpec<T> = tween(duration(120, settings), easing = FastOutLinearInEasing)
    fun lockPeak(settings: AppearanceSettings): AnimationSpec<Float> = tween(duration(70, settings), easing = LinearOutSlowInEasing)
    fun highlightMillis(settings: AppearanceSettings) = Duration * 100L / settings.speed
    fun lockEnter(settings: AppearanceSettings) = fadeIn(feedback(settings))
    fun lockExit(settings: AppearanceSettings) = fadeOut(feedback(settings))
    fun moduleTransition(backwards: Boolean, settings: AppearanceSettings): ContentTransform {
        val sign = if (backwards) -1 else 1
        return (fadeIn(transition(settings)) + slideInHorizontally(transition(settings)) { (sign * it / 12 * strength(settings)).toInt() }) togetherWith
            (fadeOut(exit(settings)) + slideOutHorizontally(exit(settings)) { (-sign * it / 12 * strength(settings)).toInt() })
    }
    fun searchTransition(toMusic: Boolean, settings: AppearanceSettings): ContentTransform {
        val sign = if (toMusic) 1 else -1
        return (fadeIn(transition(settings)) + slideInHorizontally(transition(settings)) { (sign * it / 10 * strength(settings)).toInt() }) togetherWith
            (fadeOut(exit(settings)) + slideOutHorizontally(exit(settings)) { (-sign * it / 10 * strength(settings)).toInt() })
    }
    fun searchEnter(settings: AppearanceSettings) = fadeIn(transition(settings)) + slideInVertically(transition(settings)) { (it / 10 * strength(settings)).toInt() }
    fun searchExit(settings: AppearanceSettings) = fadeOut(exit(settings)) + slideOutVertically(exit(settings)) { (it * strength(settings)).toInt() }
    fun menuEnter(settings: AppearanceSettings) = fadeIn(transition(settings))
    fun menuExit(settings: AppearanceSettings) = fadeOut(exit(settings))
    fun menuPanelEnter(settings: AppearanceSettings) = slideInHorizontally(transition(settings)) { (-it / 16 * strength(settings)).toInt() }
    fun menuPanelExit(settings: AppearanceSettings) = slideOutHorizontally(exit(settings)) { (-it / 24 * strength(settings)).toInt() }
    fun screenEnter(settings: AppearanceSettings, backwards: Boolean = false) = fadeIn(transition(settings)) +
        slideInHorizontally(transition(settings)) { ((if (backwards) -1 else 1) * it / 24 * strength(settings)).toInt() }
    fun screenExit(settings: AppearanceSettings, backwards: Boolean = false) = fadeOut(exit(settings)) +
        slideOutHorizontally(exit(settings)) { ((if (backwards) 1 else -1) * it / 32 * strength(settings)).toInt() }
    fun contextEnter(settings: AppearanceSettings) = fadeIn(feedback(settings)) + scaleIn(feedback(settings), pressScale(.97f, settings), TransformOrigin(1f, 0f))
    fun contextExit(settings: AppearanceSettings) = fadeOut(feedback(settings)) + scaleOut(feedback(settings), pressScale(.98f, settings), TransformOrigin(1f, 0f))
}
