package com.vector.messenger.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight

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
    subtitleContent: (@Composable () -> Unit)? = null,
    onContact: (() -> Unit)? = null,
    compactConversation: Boolean = false,
) {
    if (compactConversation) {
        CompactConversationHeader(title, subtitle, modifier.then(titleModifier), leading, trailing,
            avatarName, avatarPhoto, avatarId, avatarUser, subtitleContent, onContact)
        return
    }
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
            avatarName?.let { TelegramAvatar(it, avatarPhoto, 36.dp, avatarId, avatarUser, onClick = onContact); Spacer(Modifier.width(10.dp)) }
            Column(Modifier.weight(1f, fill = false).then(if (onContact != null) Modifier.clickable(onClickLabel = "Open contact profile", onClick = onContact) else Modifier), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge, color = colors.text, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                if (subtitleContent != null) subtitleContent()
                else if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall,
                    color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}

/** Mirrored 96dp side sections keep Avatar/Call symmetric and the title at screen center. */
@Composable
private fun CompactConversationHeader(
    title: String, subtitle: String, modifier: Modifier,
    leading: @Composable RowScope.() -> Unit, trailing: @Composable RowScope.() -> Unit,
    avatarName: String?, avatarPhoto: com.vector.core.profile.ProfilePhoto?, avatarId: Long, avatarUser: Boolean,
    subtitleContent: (@Composable () -> Unit)?, onContact: (() -> Unit)?,
) {
    val colors = DefaultDesign.colors
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    val subtitleHeight = with(density) { 16.sp.toDp() }
    val insets = WindowInsets.statusBars.union(WindowInsets.displayCutout)
    // Symmetric cutout clearance keeps the title at the screen center even in landscape.
    val sideInset = with(density) { maxOf(insets.getLeft(this, direction), insets.getRight(this, direction)).toDp() }
    Layout(modifier = modifier.fillMaxWidth()
        .windowInsetsPadding(insets.only(WindowInsetsSides.Top))
        .padding(horizontal = sideInset + 6.dp).height(60.dp), content = {
        Row(Modifier.width(96.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween) {
            leading()
            if (avatarName != null) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                TelegramAvatar(avatarName, avatarPhoto, 34.dp, avatarId, avatarUser, onClick = onContact)
            }
        }
        Row(Modifier.width(96.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End, content = trailing)
        Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .then(if (onContact != null) Modifier.clickable(onClickLabel = "Open contact profile", onClick = onContact) else Modifier),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp,
                lineHeight = 20.sp, fontWeight = FontWeight.Medium), color = colors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Box(Modifier.height(subtitleHeight), contentAlignment = Alignment.Center) {
                if (subtitleContent != null) subtitleContent()
                else if (subtitle.isNotEmpty()) Text(subtitle, style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
                    color = colors.secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }) { children, constraints ->
        val edges = constraints.copy(minWidth = 0, minHeight = 0)
        val left = children[0].measure(edges)
        val right = children[1].measure(edges)
        val reserved = 96.dp.roundToPx() + 6.dp.roundToPx()
        val center = children[2].measure(edges.copy(maxWidth = (constraints.maxWidth - reserved * 2).coerceAtLeast(0)))
        layout(constraints.maxWidth, constraints.maxHeight) {
            left.placeRelative(0, (constraints.maxHeight - left.height) / 2)
            right.placeRelative(constraints.maxWidth - right.width, (constraints.maxHeight - right.height) / 2)
            center.placeRelative((constraints.maxWidth - center.width) / 2, (constraints.maxHeight - center.height) / 2)
        }
    }
}
