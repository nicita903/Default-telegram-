package com.vector.messenger.feature

import androidx.lifecycle.ViewModel
import com.vector.core.profile.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class ProfileViewModel @Inject constructor(private val repository: ProfileRepository) : ViewModel() {
    val state = repository.state
    fun refresh() = repository.refresh()
}
