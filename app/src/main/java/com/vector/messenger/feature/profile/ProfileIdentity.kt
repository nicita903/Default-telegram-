package com.vector.messenger.feature.profile

import com.vector.core.profile.ProfilePhoto
import com.vector.core.profile.ProfileState
import com.vector.messenger.telegram.EditableProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged

/** Display selection is separate from session activation. Same-provider accounts have distinct keys. */
internal data class ProfileIdentityKey(val providerId: String, val accountId: String) {
    val saveKey: String get() = "${providerId.length}:$providerId$accountId"
}
internal enum class ProfileFieldCapability { Name, Username, Biography, Photo }
internal enum class ProfilePostCapability { Read, Publish, Views, Reactions, Remove, Delete }
internal data class ProfileCapabilities(val fields: Set<ProfileFieldCapability>, val posts: Set<ProfilePostCapability>) {
    val canEdit get() = fields.isNotEmpty()
    val canReadPosts get() = ProfilePostCapability.Read in posts
    val canPublish get() = ProfilePostCapability.Publish in posts
}
internal sealed interface ProfileAvatar {
    data class Telegram(val userId: Long, val photo: ProfilePhoto?) : ProfileAvatar
    data class LocalFile(val path: String) : ProfileAvatar
}
internal data class ProfileIdentity(val key: ProfileIdentityKey, val displayName: String, val username: String?,
    val biography: String?, val avatar: ProfileAvatar?, val capabilities: ProfileCapabilities)

/** Existing repositories own all account lifetimes and provider operations. This merges read-only
 * connected-account snapshots; registering another source never creates or activates a session.
 */
internal data class ConnectedProfileSource(val providerId: String, val identities: Flow<List<ProfileIdentity>>)
internal fun connectedProfileIdentities(sources: List<ConnectedProfileSource>): Flow<List<ProfileIdentity>> =
    combine(sources.map { source -> source.identities }) { snapshots ->
        snapshots.flatMapIndexed { index, identities -> identities.filter {
            it.key.providerId == sources[index].providerId && it.key.accountId.isNotBlank()
        } }.distinctBy { it.key }
    }.distinctUntilChanged()

internal object TelegramProfileIdentityAdapter {
    const val Provider = "telegram"
    private val capabilities = ProfileCapabilities(ProfileFieldCapability.entries.toSet(), ProfilePostCapability.entries.toSet())
    fun connected(account: ProfileState, details: EditableProfile?): List<ProfileIdentity> {
        val user = account.profile?.takeIf { account.authenticated } ?: return emptyList()
        return listOf(ProfileIdentity(ProfileIdentityKey(Provider, user.id.toString()), user.displayName,
            user.username?.takeIf { it.isNotBlank() }, details?.takeIf { it.account == user.id }?.bio?.takeIf { it.isNotBlank() },
            ProfileAvatar.Telegram(user.id, user.photo), capabilities))
    }
}
