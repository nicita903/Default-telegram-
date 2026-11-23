package com.vector.messenger.notifications

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class NotificationPreferences(val enabled: Boolean = true, val hideContent: Boolean = true, val groups: Boolean = true)

@Singleton
class NotificationPreferenceStore @Inject constructor(@ApplicationContext context: Context) {
    // Share the existing device-preferences storage, without persisting notification content.
    private val prefs = context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(NotificationPreferences(prefs.getBoolean("notifications.enabled", true),
        prefs.getBoolean("notifications.hideContent", true), prefs.getBoolean("notifications.groups", true)))
    val state = mutable.asStateFlow()
    fun update(change: (NotificationPreferences) -> NotificationPreferences) {
        val value = change(mutable.value)
        prefs.edit().putBoolean("notifications.enabled", value.enabled).putBoolean("notifications.hideContent", value.hideContent)
            .putBoolean("notifications.groups", value.groups).apply()
        mutable.value = value
    }
    var permissionAsked: Boolean
        get() = prefs.getBoolean("notifications.permissionAsked", false)
        set(value) { prefs.edit().putBoolean("notifications.permissionAsked", value).apply() }
}

data class DefaultMessageNotification(val providerId: String, val accountId: String, val conversationId: Long,
    val messageId: Long, val senderName: String, val preview: String, val timestamp: Long, val showPreview: Boolean, val senderKey: String = "", val senderPhoto: com.vector.core.profile.ProfilePhoto? = null) {
    override fun toString() = "DefaultMessageNotification([redacted])"
}

data class NotificationDestination(val account: Long, val chat: Long, val nonce: String)
