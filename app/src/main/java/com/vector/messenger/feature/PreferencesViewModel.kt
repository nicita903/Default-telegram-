package com.vector.messenger.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vector.core.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.*

/** Appearance persists locally; existing privacy demonstration state remains separate. */
@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val ghost: GhostRepository,
    private val savedState: SavedStateHandle,
    private val appearanceStore: AppearancePreferences,
    private val notificationStore: com.vector.messenger.notifications.NotificationPreferenceStore,
    private val soundStore: com.vector.messenger.sound.SoundPreferences,
) : ViewModel() {
    val sounds = soundStore.state
    fun soundPreference(change: (com.vector.messenger.sound.SoundSettings) -> com.vector.messenger.sound.SoundSettings) = soundStore.update(change)
    val appearance = appearanceStore.state
    fun appearance(change: (AppearanceSettings) -> AppearanceSettings) = appearanceStore.update(change)
    val ghostPreferences = ghost.preferences
    val ghostState = ghost.state
    fun ghostEnabled(value: Boolean) = ghost.enabled(value)
    fun selectGhost(id: Long) = ghost.select(id)
    fun removeGhost(id: Long) = ghost.remove(id)
    fun ghostPolicy(id: Long, value: com.vector.core.ghost.ContactPrivacy) = ghost.policy(id, value)
    fun refreshGhost() = ghost.refresh()
    fun applyGhostPrivacy() = ghost.applyPrivacy()
    fun keepCurrentPrivacy() = ghost.useCurrentRules()
    val notifications = notificationStore.state
    fun notificationPreference(change: (com.vector.messenger.notifications.NotificationPreferences) -> com.vector.messenger.notifications.NotificationPreferences) = notificationStore.update(change)
    var notificationPermissionAsked: Boolean
        get() = notificationStore.permissionAsked
        set(value) { notificationStore.permissionAsked = value }
    val hidePreviews = notifications.map { it.hideContent }.stateIn(viewModelScope, SharingStarted.Eagerly, notifications.value.hideContent)
    val moduleGestures = appearance.map { it.modules }.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.value.modules)
    fun moduleGestures(value: Boolean) { appearance { it.copy(modules = value) } }
    fun hidePreviews(value: Boolean) { notificationPreference { it.copy(hideContent = value) } }
    fun updateGhost(id: Long, value: GhostPreferences) = ghost.update(id, value)
}
