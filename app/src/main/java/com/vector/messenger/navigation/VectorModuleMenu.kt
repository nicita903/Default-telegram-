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

private val AppModule.icon: ImageVector get() = when (this) {
    AppModule.Chats -> VectorIcons.Chat
    AppModule.Contacts -> Icons.Default.Person
    AppModule.Music -> VectorIcons.Music
    AppModule.Downloads -> VectorIcons.Download
    AppModule.Privacy -> Icons.Default.Lock
    AppModule.Settings -> Icons.Default.Settings
}

@Composable
fun VectorModuleMenu(open: Boolean, active: AppModule, onDismiss: () -> Unit, onSelect: (AppModule) -> Unit, onAccount: () -> Unit = {}) {
    val transition = remember { MutableTransitionState(false) }
    LaunchedEffect(open) { transition.targetState = open }
    if (transition.currentState || transition.targetState) {
        Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            AnimatedVisibility(
                visibleState = transition,
                enter = fadeIn(VectorMotionSpec.transition()), exit = fadeOut(VectorMotionSpec.transition()),
            ) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .72f))) {
                    Box(Modifier.matchParentSize().clickable(onClick = onDismiss))
                    Surface(
                        shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                        tonalElevation = 0.dp,
                        modifier = Modifier.align(Alignment.CenterStart).safeDrawingPadding().padding(20.dp).widthIn(max = 300.dp).fillMaxWidth()
                            .animateEnterExit(enter = slideInHorizontally(VectorMotionSpec.transition()) { -it / 5 }, exit = slideOutHorizontally(VectorMotionSpec.transition()) { -it / 5 })
                            .semantics { paneTitle = "Module navigation" }
                            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
                    ) {
                        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp)) {
                            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("DEFAULT", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close navigation") }
                            }
                            Spacer(Modifier.height(8.dp))
                            AppModule.entries.forEach { module ->
                                val selected = module == active
                                val background by animateColorAsState(if (selected) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.surface, label = "Active module")
                                Row(
                                    Modifier.fillMaxWidth().background(background, RoundedCornerShape(10.dp))
                                        .semantics { this.selected = selected }
                                        .clickable(role = Role.Tab) { onSelect(module) }
                                        .padding(horizontal = 12.dp, vertical = 14.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Box(Modifier.width(3.dp).height(20.dp).background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, RoundedCornerShape(2.dp)))
                                    Icon(module.icon, null, tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 14.dp, end = 16.dp).size(20.dp))
                                    Text(module.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }
                            HorizontalDivider(Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                            TextButton(onClick = onAccount, modifier = Modifier.fillMaxWidth()) { Text("Account") }
                        }
                    }
                }
            }
        }
    }
}
