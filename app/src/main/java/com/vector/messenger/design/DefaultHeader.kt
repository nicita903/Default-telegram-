package com.vector.messenger.design

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Bare header: no surface, no panel, just controls and a centred title over the colour field.
 * The leading and trailing slots are supplied by the caller, so the same bar serves every module:
 * Roots put Menu on the left and Chats puts New chat on the right; nested screens retain Back.
 * Presentation only; the caller retains all screen/module/system-back decisions.
 */
@Composable
fun DefaultHeader(
    title: String, subtitle: String, modifier: Modifier = Modifier, titleModifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
    avatarName: String? = null, avatarPhoto: com.vector.core.profile.ProfilePhoto? = null,
    avatarId: Long = 0, avatarUser: Boolean = false,
) {
    val colors = DefaultDesign.colors
    Box(
        modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .heightIn(min = 52.dp)
            .then(titleModifier),
    ) {
        Row(
            Modifier.align(Alignment.CenterStart).heightIn(min = DefaultDesign.minimumTarget),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = leading,
        )
        Row(
            Modifier.align(Alignment.CenterEnd).heightIn(min = DefaultDesign.minimumTarget),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = trailing,
        )
        // Centred against the bar, not against the remaining space between the slots, so the
        // title does not drift when a module has no leading action. Side padding keeps it clear
        // of one 48 dp control on either side. Register the whole bare header above as the
        // gesture surface; registering only this title left the surrounding space inactive.
        Row(
            Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = 56.dp).heightIn(min = 48.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            avatarName?.let { TelegramAvatar(it, avatarPhoto, 36.dp, avatarId, avatarUser); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f, fill = false), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
