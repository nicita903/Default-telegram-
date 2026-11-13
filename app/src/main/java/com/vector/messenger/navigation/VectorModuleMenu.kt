package com.vector.messenger.navigation

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.design.VectorIcons
import com.vector.messenger.design.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import com.vector.messenger.feature.LocalAppearance

private val AppModule.icon: ImageVector get() = when (this) {
    AppModule.Chats -> VectorIcons.Chat
    AppModule.Contacts -> Icons.Default.Person
    AppModule.Music -> VectorIcons.Music
    AppModule.Downloads -> VectorIcons.Download
    AppModule.Privacy -> Icons.Default.Lock
    AppModule.Settings -> Icons.Default.Settings
}

@Composable
fun VectorModuleMenu(open: Boolean, active: AppModule, onDismiss: () -> Unit, onSelect: (AppModule) -> Unit, onAccount: () -> Unit = {}, onSearch: () -> Unit = {}) {
    val appearance = LocalAppearance.current
    val travel = if (appearance.reducedMotion) 0f else appearance.intensity / 100f
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { transition.targetState = open }
    if (transition.currentState || transition.targetState) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AnimatedVisibility(
                visibleState = transition,
                enter = fadeIn(VectorMotionSpec.transition(appearance)), exit = fadeOut(VectorMotionSpec.transition(appearance)),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f))) {
                    Box(Modifier.matchParentSize().clickable(onClick = onDismiss))
                    Surface(
                        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(20.dp).widthIn(max = 300.dp).fillMaxWidth()
                            .animateEnterExit(enter = slideInHorizontally(VectorMotionSpec.transition(appearance)) { (-it / 5 * travel).toInt() }, exit = slideOutHorizontally(VectorMotionSpec.transition(appearance)) { (-it / 5 * travel).toInt() })
                            .semantics { paneTitle = "Module navigation" }
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("Default", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                DefaultIconControl(Icons.Default.Close, "Close navigation", onDismiss, quiet = true)
                            }
                            Spacer(Modifier.height(8.dp))
                            AppModule.entries.forEach { module ->
                                val selected = module == active
                                val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "Active module")
                                Row(
                                    Modifier.fillMaxWidth().background(background, RoundedCornerShape(24.dp))
                                        .semantics { this.selected = selected }
                                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(module) }
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.size(36.dp).background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .12f) else MaterialTheme.colorScheme.surfaceContainer, CircleShape), contentAlignment = Alignment.Center) {
                                        Icon(module.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                                    }
                                    Spacer(Modifier.width(14.dp))
                                    Text(module.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            DefaultAction("Search", onSearch, Modifier.fillMaxWidth(), Icons.Default.Search)
                            DefaultAction("Account", onAccount, Modifier.fillMaxWidth(), Icons.Default.Person)
                        }
                    }
                }
            }
        }
    }
}
