package com.vector.messenger.feature

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.vector.core.profile.*
import com.vector.messenger.design.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Private account information, hosted inside the existing FLAG_SECURE account dialog. */
@Composable
internal fun AccountProfileContent(state: ProfileState, refresh: () -> Unit) {
    val colors = DefaultDesign.colors
    val profile = state.profile
    Column(Modifier.fillMaxWidth().testTag("telegram_profile"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!state.authenticated) {
            Text("No Telegram account signed in", color = colors.secondary)
        } else {
            if (profile != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    ProfileAvatar(profile)
                    Column(Modifier.weight(1f)) {
                        Text(profile.displayName, style = MaterialTheme.typography.titleLarge, color = colors.text)
                        Text(profile.username?.let { "@$it" } ?: "No username", style = MaterialTheme.typography.bodySmall, color = colors.secondary)
                    }
                }
                DefaultRule()
                ProfileField("First name", profile.firstName.ifBlank { "Not set" })
                ProfileField("Last name", profile.lastName.ifBlank { "Not set" })
                ProfileField("Username", profile.username?.let { "@$it" } ?: "Not set")
                ProfileField("Phone", profile.phoneNumber?.let { if (it.startsWith('+')) it else "+$it" } ?: "Not available")
                ProfileField("Account ID", profile.id.toString())
            } else if (state.loading) Text("Loading Telegram profile…", color = colors.secondary)
            else Text("Profile unavailable", color = colors.secondary)
            when (state.connection) {
                ProfileConnection.WaitingForNetwork -> Text("Network unavailable · cached information may be shown", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                ProfileConnection.Connecting -> Text("Connecting to Telegram…", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                ProfileConnection.Updating -> Text("Updating Telegram connection…", color = colors.secondary, style = MaterialTheme.typography.bodySmall)
                ProfileConnection.Ready -> Unit
            }
            state.failure?.let { failure ->
                Text(if (failure == ProfileFailure.Network) "Unable to refresh. Check your connection." else "Unable to load your Telegram profile. Try again.",
                    color = colors.secondary, style = MaterialTheme.typography.bodySmall)
            }
            DefaultAction(if (state.loading) "Refreshing…" else "Refresh profile", refresh, enabled = !state.loading)
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.width(96.dp), color = DefaultDesign.colors.secondary, style = MaterialTheme.typography.bodySmall)
        Text(value, Modifier.weight(1f), color = DefaultDesign.colors.text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ProfileAvatar(profile: TelegramProfile) {
    val path = profile.photo?.localPath
    key(profile.id, profile.photo?.fileId, path) {
        // A new identity/photo gets a new producer, so no previous bitmap survives for a frame.
        // Decode only the small TDLib image, off Main, with bounded dimensions. No external URL/cache.
        val bitmap by produceState<ImageBitmap?>(null) {
            value = withContext(Dispatchers.IO) {
                if (path == null) null else try {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null else {
                        var sample = 1
                        while (bounds.outWidth / sample > 256 || bounds.outHeight / sample > 256) sample *= 2
                        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
                    }
                } catch (_: Exception) { null }
            }
        }
        if (bitmap == null) ChatAvatar(profile.displayName, 64.dp)
        else Image(bitmap!!, "Telegram profile picture", Modifier.size(64.dp).clip(DefaultDesign.avatarShape)
, contentScale = ContentScale.Crop)
    }
}
