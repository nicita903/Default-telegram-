package com.vector.core.profile

import com.vector.core.auth.AuthStep
import com.vector.core.auth.AuthenticationRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Session-scoped observation and requests. The injected process scope serializes reducers on Main. */
class DefaultProfileRepository(
    authentication: AuthenticationRepository,
    private val client: TelegramProfileClient,
    scope: CoroutineScope,
) : ProfileRepository {
    private val mutable = MutableStateFlow(ProfileState())
    override val state = mutable.asStateFlow()
    private val refreshes = MutableStateFlow(0L)
    override fun refresh() { refreshes.update { it + 1 } }

    init {
        scope.launch {
            authentication.state.map { it.step == AuthStep.Ready }.distinctUntilChanged().collectLatest { ready ->
                mutable.value = ProfileState(authenticated = ready, loading = ready, connection = client.connection.value)
                if (!ready) return@collectLatest
                coroutineScope {
                    var revision = 0L
                    var photoJob: Job? = null
                    var requestedPhoto: Int? = null
                    fun applyPhoto(photo: ProfilePhoto, preserveCompleted: Boolean = false) {
                        mutable.update { current ->
                            val profile = current.profile
                            if (profile?.photo?.fileId != photo.fileId) current
                            else current.copy(profile = profile.copy(photo = if (preserveCompleted && photo.localPath == null)
                                photo.copy(localPath = profile.photo.localPath) else photo))
                        }
                    }
                    fun applyUser(user: TelegramProfile) {
                        revision++
                        val previous = mutable.value.profile
                        val photo = user.photo?.let { incoming ->
                            if (previous?.id == user.id && previous.photo?.fileId == incoming.fileId)
                                incoming.copy(localPath = incoming.localPath ?: previous.photo.localPath) else incoming
                        }
                        mutable.update { it.copy(profile = user.copy(photo = photo), loading = false, failure = null) }
                        if (photo?.fileId != requestedPhoto) {
                            photoJob?.cancel(); requestedPhoto = photo?.fileId
                            if (photo != null && photo.localPath == null) photoJob = launch {
                                try {
                                    val file = client.downloadPhoto(photo.fileId)
                                    ensureActive(); applyPhoto(file, preserveCompleted = true)
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (_: Exception) { /* Profile text stays usable; refresh can retry the photo. */ }
                            }
                        }
                    }
                    // Subscribe before getMe: an update received while a request is pending wins over its response.
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        client.profileUpdates.collect { update ->
                            when (update) {
                                is ProfileUpdate.CurrentUser -> applyUser(update.profile)
                                is ProfileUpdate.Photo -> applyPhoto(update.photo)
                            }
                        }
                    }
                    launch { client.connection.collect { value -> mutable.update { it.copy(connection = value) } } }
                    refreshes.collectLatest {
                        val startedAt = revision
                        // A manual refresh also retries an absent/downloading photo without changing identity.
                        requestedPhoto = null
                        mutable.update { it.copy(loading = true, failure = null) }
                        try {
                            val profile = client.getMe()
                            ensureActive()
                            if (revision == startedAt) applyUser(profile)
                            mutable.update { it.copy(loading = false) }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            if (revision == startedAt) mutable.update {
                                it.copy(loading = false, failure = (failure as? ProfileRequestException)?.failure ?: ProfileFailure.Unavailable)
                            }
                        }
                    }
                }
            }
        }
    }
}
