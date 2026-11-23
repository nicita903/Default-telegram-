package com.vector.messenger.feature.profile

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import com.vector.core.media.ViewerAction
import com.vector.core.media.viewerRelease

/** Only the video picture region owns this detector. Seek/playback controls and captions stay
 * outside it; photos continue using the existing zoom-aware media viewer recognizer.
 */
@Composable
internal fun Modifier.storyVideoGestures(key: Any, onNavigate: (Int) -> Unit, onClose: () -> Unit): Modifier {
    val density = LocalDensity.current.density
    val slop = LocalViewConfiguration.current.touchSlop
    val navigate by rememberUpdatedState(onNavigate)
    val close by rememberUpdatedState(onClose)
    return pointerInput(key, density, slop) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (down.position.x < 24 * density || down.position.x > size.width - 24 * density) return@awaitEachGesture
            var delta = Offset.Zero
            var moved = false
            var canceled = false
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.count { it.pressed } > 1 || event.changes.any { it.isConsumed }) { canceled = true; break }
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (moved && !canceled) when (viewerRelease(delta.x, delta.y, 1f, false, false, density)) {
                        ViewerAction.Next -> navigate(1)
                        ViewerAction.Previous -> navigate(-1)
                        ViewerAction.Dismiss -> close()
                        else -> Unit
                    }
                    break
                }
                delta = change.position - down.position
                if (delta.getDistance() > slop) moved = true
                if (moved) change.consume()
            }
        }
    }
}
