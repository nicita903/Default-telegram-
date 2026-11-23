package com.vector.messenger.feature.avatar

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.messenger.design.*

@Composable
fun OwnAccountAvatar(size: Dp = 44.dp) {
    val avatars = LocalAvatarViewModel.current ?: return
    val state by avatars.ownProfile.collectAsStateWithLifecycle()
    val profile = state.profile?.takeIf { state.authenticated } ?: return
    TelegramAvatar(profile.displayName, profile.photo, size, profile.id, user = true)
}

@Composable
fun AccountSummary() {
    val avatars = LocalAvatarViewModel.current ?: return
    val state by avatars.ownProfile.collectAsStateWithLifecycle()
    val account = LocalAccountAction.current
    val profile = state.profile?.takeIf { state.authenticated }
    if (profile == null) DefaultAction("Telegram account", account)
    else Row(Modifier.fillMaxWidth().clickable(onClick = account).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        OwnAccountAvatar(72.dp)
        Column {
            Text(profile.displayName, color = DefaultDesign.colors.text, style = MaterialTheme.typography.titleMedium)
            Text(profile.username?.let { "@$it" } ?: "Telegram account", color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
