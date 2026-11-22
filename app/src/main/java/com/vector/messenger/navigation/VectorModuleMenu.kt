package com.vector.messenger.navigation

import com.vector.messenger.design.DesignIcon as Icon

import androidx.compose.animation.*
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.vector.messenger.design.VectorMotionSpec
import com.vector.messenger.design.VectorIcons
import com.vector.messenger.design.*
import com.vector.messenger.feature.LocalAppearance

private val AppModule.icon: ImageVector get() = when (this) {
    AppModule.Chats -> VectorIcons.Chat
    AppModule.Contacts -> Icons.Default.Person
    AppModule.Music -> VectorIcons.Music
    AppModule.Downloads -> VectorIcons.Download
    AppModule.Privacy -> Icons.Default.Lock
    AppModule.Settings -> Icons.Default.Settings
}

/**
 * Full-screen module navigation. It is an in-window surface rather than a dialog, so it covers the
 * whole display, keeps the Aurora field visible behind it, and does not open a second window.
 * The screen it covers is blurred by the caller (see `backdropBlur`); the scrim here stays
 * translucent only while that blur actually renders, so the list is never sharp behind the rows.
 * Dismissal stays on the close control, module selection and Android Back, all owned by the caller.
 */
@Composable
fun VectorModuleMenu(open: Boolean, active: AppModule, onDismiss: () -> Unit, onSelect: (AppModule) -> Unit, onAccount: () -> Unit = {}, onSearch: () -> Unit = {}) {
    val appearance = LocalAppearance.current
    val colors = DefaultDesign.colors
    val scrim = backdropScrim()
    val transition = remember { MutableTransitionState(false) }.apply { targetState = open }
    if (transition.currentState || transition.targetState) {
        AnimatedVisibility(
            visibleState = transition,
            enter = VectorMotionSpec.menuEnter(appearance),
            exit = VectorMotionSpec.menuExit(appearance),
        ) {
            Column(
                Modifier.fillMaxSize()
                    .background(colors.base.copy(alpha = scrim))
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .testTag("module_menu")
                    .semantics { paneTitle = "Module navigation" }
                    // A full-screen sheet must swallow taps on its empty areas, otherwise a tap
                    // beside the last row would reach the list behind it.
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .verticalScroll(rememberScrollState()),
            ) {
                Row(Modifier.fillMaxWidth().padding(start = 22.dp, end = 12.dp, top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Default", style = MaterialTheme.typography.headlineSmall, color = colors.text)
                        Text("Modules", style = MaterialTheme.typography.labelSmall,
                            color = colors.secondary, modifier = Modifier.semantics { heading() })
                    }
                    DefaultIconControl(Icons.Default.Close, "Close navigation", onDismiss, quiet = true)
                }
                Spacer(Modifier.height(10.dp))
                AppModule.entries.forEach { module ->
                    val selected = module == active
                    val background by animateColorAsState(
                        if (selected) colors.accent.copy(alpha = .09f) else Color.Transparent,
                        VectorMotionSpec.feedback(appearance), label = "Active module",
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
                            .background(background, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                            .semantics { this.selected = selected }
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Tab) { onSelect(module) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.size(36.dp)
                                .background(if (selected) colors.accent.copy(alpha = .12f) else Color.Transparent, DefaultDesign.avatarShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(module.icon, null, tint = if (selected) colors.active else colors.secondary, modifier = Modifier.size(20.dp))
                        }
                        Spacer(Modifier.width(16.dp))
                        Text(module.label, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f), color = if (selected) colors.active else colors.text)
                        if (selected) Box(Modifier.size(6.dp).background(colors.active, CircleShape))
                    }
                }
                HorizontalDivider(Modifier.padding(horizontal = 20.dp, vertical = 14.dp), color = colors.line)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DefaultAction("Search", onSearch, Modifier.weight(1f), Icons.Default.Search)
                    DefaultAction("Account", onAccount, Modifier.weight(1f), Icons.Default.Person)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}
