package com.vector.messenger.notifications

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.material3.*
import com.vector.messenger.feature.PreferencesViewModel
import com.vector.messenger.feature.PreferenceToggle
import com.vector.messenger.design.DefaultAction
import com.vector.messenger.design.DefaultDialog
import com.vector.messenger.design.VectorIcons
import com.vector.messenger.feature.CompactSettingsRow
import com.vector.messenger.feature.SettingsHeading
import com.vector.messenger.feature.SettingsIcons

@Composable
fun NotificationSettings(viewModel: PreferencesViewModel) {
    val context = LocalContext.current
    val value by viewModel.notifications.collectAsStateWithLifecycle()
    var permitted by remember { mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled()) }
    var rationale by remember { mutableStateOf(false) }
    var systemError by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permitted = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { permitted = NotificationManagerCompat.from(context).areNotificationsEnabled() }
    SettingsHeading("On this device")
    PreferenceToggle("Message notifications", value.enabled) { enabled ->
        viewModel.notificationPreference { it.copy(enabled = enabled) }
        if (enabled && Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && !viewModel.notificationPermissionAsked) rationale = true
    }
    PreferenceToggle("Message previews", !value.hideContent) { show -> viewModel.notificationPreference { it.copy(hideContent = !show) } }
    PreferenceToggle("Group alerts", value.groups) { groups -> viewModel.notificationPreference { it.copy(groups = groups) } }
    if (!permitted && Build.VERSION.SDK_INT >= 33 && !viewModel.notificationPermissionAsked) CompactSettingsRow("Allow notifications", icon = VectorIcons.Check) { rationale = true }
    SettingsHeading("System")
    if (!permitted) Text("Android notifications are off.", style = MaterialTheme.typography.bodySmall)
    CompactSettingsRow("Android notification settings", if (permitted) "Allowed" else "Off", SettingsIcons.Bell) {
        try { context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)) }
        catch (_: Exception) { systemError = "Android notification settings are unavailable in this profile." }
    }
    systemError?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (rationale) DefaultDialog("Allow message notifications?", { rationale = false; viewModel.notificationPermissionAsked = true }) {
        Text("Default uses notifications for incoming messages and direct replies. You can hide previews or change access in Android settings.", style = MaterialTheme.typography.bodyMedium)
        CompactSettingsRow("Continue", icon = VectorIcons.Check, chevron = false) {
            rationale = false; viewModel.notificationPermissionAsked = true
            if (Build.VERSION.SDK_INT >= 33) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        CompactSettingsRow("Not now", icon = VectorIcons.Minus, chevron = false) { rationale = false; viewModel.notificationPermissionAsked = true }
    }
}
