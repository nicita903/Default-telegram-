package com.vector.messenger.design

import androidx.compose.animation.core.*

/** Compose animations inherit Android's animator duration scale, including disabled animation. */
object VectorMotionSpec {
    const val Duration = 180
    const val ActivatedScale = .95f
    fun scale(): AnimationSpec<Float> = spring(dampingRatio = .9f, stiffness = Spring.StiffnessMedium)
    fun <T> transition(): FiniteAnimationSpec<T> = tween(Duration, easing = FastOutSlowInEasing)
}
