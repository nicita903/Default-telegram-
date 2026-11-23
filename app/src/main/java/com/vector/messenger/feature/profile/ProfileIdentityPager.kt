package com.vector.messenger.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.DefaultDesign
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import kotlinx.coroutines.launch

/** Pager changes only the displayed key. Provider sessions, the global menu and module history
 * are never changed by swiping. Each page's lazy-list/saveable state is keyed by account identity.
 */
@Composable
internal fun ProfileIdentityPager(identities: List<ProfileIdentity>, selected: String,
    onSelected: (ProfileIdentityKey) -> Unit, page: @Composable (ProfileIdentity) -> Unit) {
    if (identities.isEmpty()) return
    val pager = rememberPagerState(initialPage = identities.indexOfFirst { it.key.saveKey == selected }.coerceAtLeast(0), pageCount = { identities.size })
    val appearance = LocalAppearance.current
    val scope = rememberCoroutineScope()
    val keys = remember(identities) { identities.map { it.key.saveKey } }
    // Reorders/disconnections restore the selected identity rather than its old numeric index.
    LaunchedEffect(keys) {
        val wanted = keys.indexOf(selected).takeIf { it >= 0 } ?: pager.currentPage.coerceIn(0, identities.lastIndex)
        if (wanted != pager.currentPage) pager.scrollToPage(wanted)
        onSelected(identities[wanted].key)
    }
    LaunchedEffect(pager, keys) {
        snapshotFlow { pager.settledPage }.collect { index -> identities.getOrNull(index)?.let { onSelected(it.key) } }
    }
    Column(Modifier.fillMaxSize()) {
        if (identities.size > 1) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            identities.forEachIndexed { index, identity -> key(identity.key.saveKey) {
                Box(Modifier.size(36.dp).excludeModuleGestures().semantics {
                    role = Role.Tab; this.selected = pager.currentPage == index
                    contentDescription = "${identity.key.providerId}: ${identity.displayName}"
                }.clickable {
                    scope.launch {
                        if (VectorMotionSpec.strength(appearance) == 0f) pager.scrollToPage(index)
                        else pager.animateScrollToPage(index, animationSpec = VectorMotionSpec.transition(appearance))
                    }
                }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(6.dp).background(DefaultDesign.colors.accent.copy(alpha = if (pager.currentPage == index) 1f else .25f), DefaultDesign.avatarShape))
                }
            } }
        }
        HorizontalPager(state = pager, key = { identities[it].key.saveKey }, userScrollEnabled = identities.size > 1,
            modifier = Modifier.weight(1f).then(if (identities.size > 1) Modifier.excludeModuleGestures() else Modifier)) { index ->
            key(identities[index].key.saveKey) { page(identities[index]) }
        }
    }
}
