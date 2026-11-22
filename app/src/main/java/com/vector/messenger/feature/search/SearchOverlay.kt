package com.vector.messenger.feature.search

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.design.rememberInteractiveOffset
import com.vector.messenger.feature.LocalAppearance

/** Always composed above the existing Chats host; only the overlay's visibility changes. */
@Composable
fun SearchOverlay(
    visibility: MutableTransitionState<Boolean>, inputEnabled: Boolean, viewModel: SearchViewModel,
    scope: SearchScope, blockedAttempt: Int, onScope: (SearchScope) -> Unit, onClose: () -> Unit, onOpen: (Long) -> Unit,
) {
    val appearance = LocalAppearance.current
    val chatList = rememberLazyListState()
    val musicList = rememberLazyListState()
    val holder = rememberSaveableStateHolder()
    val regions = remember { SearchRegions() }
    val pull = remember { SearchPullMotion() }
    val threshold = with(LocalDensity.current) { appearance.pullDistance.dp.toPx() }
    val travel = VectorMotionSpec.strength(appearance)
    val translation = rememberInteractiveOffset({ searchPullOffset(pull.raw, threshold) * travel }, { pull.dragging },
        enabled = visibility.currentState || visibility.targetState)
    LaunchedEffect(visibility.currentState, visibility.targetState) {
        if (!visibility.currentState && !visibility.targetState) pull.reset()
    }
    CompositionLocalProvider(LocalSearchRegions provides regions) {
        AnimatedVisibility(visibleState = visibility, modifier = Modifier.fillMaxSize().clipToBounds(),
            enter = VectorMotionSpec.searchEnter(appearance), exit = VectorMotionSpec.searchExit(appearance)) {
            Box(Modifier.fillMaxSize().testTag("search_layer").searchWorkspaceInput(scope, inputEnabled, appearance, pull,
                resultsAtTop = { !(if (scope == SearchScope.Chats) chatList else musicList).canScrollBackward },
                onDismiss = onClose, onSwitch = { direction ->
                    val target = if (direction == SearchGestureAction.NextScope) SearchScope.Music else SearchScope.Chats
                    if (target != scope) onScope(target)
                })) {
                Box(Modifier.fillMaxSize().graphicsLayer {
                    translationY = if (pull.dragging) searchPullOffset(pull.raw, threshold) * travel else translation.value
                }) {
                    AnimatedContent(scope, transitionSpec = {
                        VectorMotionSpec.searchTransition(targetState == SearchScope.Music, appearance).using(null)
                    }, label = "Search scope") { selected ->
                        holder.SaveableStateProvider(selected.name) {
                            CompositionLocalProvider(LocalSearchScope provides selected) {
                                com.vector.messenger.design.MusicPalette(enabled = selected == SearchScope.Music) {
                                SearchWorkspace(viewModel, selected, blockedAttempt, onScope, onOpen, onClose,
                                    listState = if (selected == SearchScope.Chats) chatList else musicList)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
