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
) : ViewModel() {
    val appearance = appearanceStore.state
    fun appearance(change: (AppearanceSettings) -> AppearanceSettings) = appearanceStore.update(change)
    val ghostPreferences = ghost.preferences
    val theme = appearanceStore.theme
    val hidePreviews = savedState.getStateFlow("hidePreviews", true)
    val moduleGestures = appearance.map { it.modules }.stateIn(viewModelScope, SharingStarted.Eagerly, appearance.value.modules)
    fun moduleGestures(value: Boolean) { appearance { it.copy(modules = value) } }
    fun theme(value: ThemeMode) { appearanceStore.theme(value.name) }
    fun hidePreviews(value: Boolean) { savedState["hidePreviews"] = value }
    fun updateGhost(id: Long, value: GhostPreferences) = ghost.update(id, value)
}
