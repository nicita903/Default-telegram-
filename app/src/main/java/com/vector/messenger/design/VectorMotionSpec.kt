package com.vector.messenger.design

import androidx.compose.animation.core.*
import androidx.compose.runtime.Composable
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.AppearanceSettings

/** Compose animations inherit Android's animator duration scale, including disabled animation. */
object VectorMotionSpec {
    const val Duration = 180
    const val ActivatedScale = .95f
    @Composable fun pressScale(target: Float): Float {
        val settings = LocalAppearance.current
        return if (settings.reducedMotion) 1f else 1f - (1f - target) * settings.intensity / 100f
    }
    @Composable fun scale(): AnimationSpec<Float> {
        val settings = LocalAppearance.current
        return if (settings.reducedMotion || settings.intensity == 0) snap() else spring(dampingRatio = .9f, stiffness = Spring.StiffnessMedium * settings.speed / 100f)
    }
    fun <T> transition(settings: AppearanceSettings = AppearanceSettings()): FiniteAnimationSpec<T> {
        return tween(if (settings.reducedMotion || settings.intensity == 0) 0 else Duration * 100 / settings.speed, easing = FastOutSlowInEasing)
    }
}
