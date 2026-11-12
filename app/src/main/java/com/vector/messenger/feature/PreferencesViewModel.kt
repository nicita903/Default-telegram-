package com.vector.messenger.feature

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.vector.core.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** Phase 1 preferences are non-sensitive demonstration state, restored with the activity. */
@HiltViewModel
class PreferencesViewModel @Inject constructor(
    private val ghost: GhostRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {
    val ghostPreferences = ghost.preferences
    val theme = savedState.getStateFlow("theme", ThemeMode.System.name)
    val hidePreviews = savedState.getStateFlow("hidePreviews", true)
    val moduleGestures = savedState.getStateFlow("moduleGestures", true)
    fun moduleGestures(value: Boolean) { savedState["moduleGestures"] = value }
    fun theme(value: ThemeMode) { savedState["theme"] = value.name }
    fun hidePreviews(value: Boolean) { savedState["hidePreviews"] = value }
    fun updateGhost(id: Long, value: GhostPreferences) = ghost.update(id, value)
}
