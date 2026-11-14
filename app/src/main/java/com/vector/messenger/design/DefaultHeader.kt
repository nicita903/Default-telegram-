package com.vector.messenger.design

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vector.messenger.navigation.gesture.excludeModuleGestures

/** Header presentation only; the caller retains all screen/module/system-back decisions. */
@Composable
fun DefaultHeader(
    title: String, subtitle: String, navigationIcon: ImageVector, navigationDescription: String,
    onNavigation: () -> Unit, modifier: Modifier = Modifier, titleModifier: Modifier = Modifier,
    avatarName: String? = null, onMenu: (() -> Unit)? = null,
    avatarPhoto: com.vector.core.profile.ProfilePhoto? = null,
) {
    val colors = DefaultDesign.colors
    Column(modifier.background(colors.base).windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            DefaultIconControl(navigationIcon, navigationDescription, onNavigation, Modifier.excludeModuleGestures())
            Row(Modifier.weight(1f).heightIn(min = 48.dp).then(titleModifier).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                avatarName?.let { TelegramAvatar(it, avatarPhoto, 34.dp); Spacer(Modifier.width(12.dp)) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            onMenu?.let { DefaultIconControl(Icons.Default.Menu, "Open navigation", it, Modifier.excludeModuleGestures()) }
        }
        DefaultRule()
    }
}
