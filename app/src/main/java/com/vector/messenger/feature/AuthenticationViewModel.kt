package com.vector.messenger.feature

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.auth.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

@HiltViewModel
class AuthenticationViewModel @Inject constructor(private val repository: AuthenticationRepository) : ViewModel() {
    val state = repository.state
    init { repository.start() }
    fun submit(action: AuthAction) { viewModelScope.launch { repository.submit(action) } }
    fun start() = repository.start()
}
