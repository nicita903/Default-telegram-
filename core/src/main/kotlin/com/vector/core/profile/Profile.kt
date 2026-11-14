package com.vector.core.profile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ProfilePhoto(val fileId: Int, val localPath: String? = null) {
    override fun toString() = "ProfilePhoto([redacted])"
}

data class TelegramProfile(
    val id: Long, val firstName: String, val lastName: String,
    val username: String?, val phoneNumber: String?, val photo: ProfilePhoto?,
) {
    val displayName get() = listOf(firstName, lastName).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Telegram account" }
    override fun toString() = "TelegramProfile([redacted])"
}

enum class ProfileConnection { Connecting, WaitingForNetwork, Updating, Ready }
enum class ProfileFailure { Network, Unavailable }
data class ProfileState(
    val authenticated: Boolean = false, val loading: Boolean = false, val profile: TelegramProfile? = null,
    val connection: ProfileConnection = ProfileConnection.Connecting, val failure: ProfileFailure? = null,
) { override fun toString() = "ProfileState(authenticated=$authenticated, loading=$loading, connection=$connection, failure=$failure)" }

sealed interface ProfileUpdate {
    data class CurrentUser(val profile: TelegramProfile) : ProfileUpdate
    data class Photo(val photo: ProfilePhoto) : ProfileUpdate
}

class ProfileRequestException(val failure: ProfileFailure) : Exception("Telegram profile request failed")

/** Shares the existing authenticated TDLib transport; never creates or starts a client. */
interface TelegramProfileClient {
    val profileUpdates: Flow<ProfileUpdate>
    val connection: StateFlow<ProfileConnection>
    suspend fun getMe(): TelegramProfile
    suspend fun downloadPhoto(fileId: Int): ProfilePhoto
}

interface ProfileRepository {
    val state: StateFlow<ProfileState>
    fun refresh()
}
