package com.vector.core

import com.vector.core.auth.*
import com.vector.core.profile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProfileRepositoryTest {
    private val user = TelegramProfile(1234567890123, "Test", "Account", "test_handle", "12025550123", null)
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Phone))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
        fun step(step: AuthStep) { state.value = AuthState(step) }
    }
    private class Client(var user: TelegramProfile) : TelegramProfileClient {
        override val profileUpdates = MutableSharedFlow<ProfileUpdate>(extraBufferCapacity = 16)
        override val connection = MutableStateFlow(ProfileConnection.Ready)
        var calls = 0
        var gate: CompletableDeferred<Unit>? = null
        var failure: ProfileFailure? = null
        val photos = mutableListOf<Int>()
        override suspend fun getMe(): TelegramProfile {
            calls++; val snapshot = user; gate?.await()
            failure?.let { throw ProfileRequestException(it) }
            return snapshot
        }
        override suspend fun downloadPhoto(fileId: Int): ProfilePhoto { photos += fileId; return ProfilePhoto(fileId) }
    }
    @Test fun requestsOnlyAfterReadyAndRestoresCurrentSessionWithoutStartingAnotherClient() = runTest {
        val auth = Auth(); val client = Client(user)
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        runCurrent(); assertEquals(0, client.calls); assertFalse(repo.state.value.authenticated)
        auth.step(AuthStep.Ready); runCurrent()
        assertEquals(user, repo.state.value.profile); assertFalse(repo.state.value.loading)
        auth.state.value = AuthState(AuthStep.Ready, busy = true); runCurrent()
        assertEquals(1, client.calls) // Auth submission flags must not recreate the profile session.
    }
    @Test fun missingNamesUsernameAndPhotoHaveNoInventedIdentityOrDownload() = runTest {
        val auth = Auth(); val client = Client(user.copy(firstName = "", lastName = "", username = null, phoneNumber = null))
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent()
        assertEquals("Telegram account", repo.state.value.profile?.displayName)
        assertNull(repo.state.value.profile?.username); assertNull(repo.state.value.profile?.photo)
        assertTrue(client.photos.isEmpty())
    }
    @Test fun newerUserUpdateWinsOverAnInflightRefreshAndCanRemoveFields() = runTest {
        val auth = Auth(); val client = Client(user)
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent()
        client.gate = CompletableDeferred(); repo.refresh(); runCurrent()
        val updated = user.copy(firstName = "Changed", lastName = "", username = null, phoneNumber = null)
        client.profileUpdates.emit(ProfileUpdate.CurrentUser(updated)); runCurrent()
        client.gate!!.complete(Unit); runCurrent()
        assertEquals(updated, repo.state.value.profile)
        assertFalse(repo.state.value.loading)
    }
    @Test fun photoCompletionUpdatesOnlyCurrentPhotoAndPhotoRemovalDoesNotResurrectIt() = runTest {
        val auth = Auth(); val client = Client(user.copy(photo = ProfilePhoto(7)))
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent(); assertEquals(listOf(7), client.photos)
        client.profileUpdates.emit(ProfileUpdate.Photo(ProfilePhoto(8, "/ignored"))); runCurrent()
        assertNull(repo.state.value.profile?.photo?.localPath)
        client.profileUpdates.emit(ProfileUpdate.Photo(ProfilePhoto(7, "/test/photo"))); runCurrent()
        assertEquals("/test/photo", repo.state.value.profile?.photo?.localPath)
        client.profileUpdates.emit(ProfileUpdate.CurrentUser(client.user)); runCurrent()
        assertEquals("/test/photo", repo.state.value.profile?.photo?.localPath)
        client.profileUpdates.emit(ProfileUpdate.CurrentUser(user)); runCurrent()
        client.profileUpdates.emit(ProfileUpdate.Photo(ProfilePhoto(7, "/late"))); runCurrent()
        assertNull(repo.state.value.profile?.photo)
    }
    @Test fun logoutClearsIdentityAndPendingRequestsCannotPopulateTheNextAccount() = runTest {
        val auth = Auth(); val client = Client(user)
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent()
        client.gate = CompletableDeferred(); repo.refresh(); runCurrent()
        auth.step(AuthStep.LoggingOut); runCurrent()
        assertNull(repo.state.value.profile); assertFalse(repo.state.value.authenticated)
        client.gate!!.complete(Unit); runCurrent()
        assertNull(repo.state.value.profile)
        auth.step(AuthStep.Closed); runCurrent()
        client.gate = null; client.user = user.copy(id = 99, firstName = "Another")
        auth.step(AuthStep.Ready); runCurrent()
        assertEquals(99L, repo.state.value.profile?.id)
    }
    @Test fun networkFailureKeepsCachedIdentityAndManualRetryRecovers() = runTest {
        val auth = Auth(); val client = Client(user)
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent()
        client.connection.value = ProfileConnection.WaitingForNetwork; runCurrent()
        assertEquals(user, repo.state.value.profile)
        assertEquals(ProfileConnection.WaitingForNetwork, repo.state.value.connection)
        assertEquals(1, client.calls) // Connection updates describe status, never gate TDLib calls.
        client.failure = ProfileFailure.Network; repo.refresh(); runCurrent()
        assertEquals(user, repo.state.value.profile); assertEquals(ProfileFailure.Network, repo.state.value.failure)
        client.failure = null; client.connection.value = ProfileConnection.Ready
        repo.refresh(); runCurrent()
        assertNull(repo.state.value.failure); assertFalse(repo.state.value.loading)
    }
    @Test fun failedInitialRequestShowsNoFabricatedProfileAndAuthLossStopsObservation() = runTest {
        val auth = Auth(); val client = Client(user).apply { failure = ProfileFailure.Unavailable }
        val repo = DefaultProfileRepository(auth, client, backgroundScope)
        auth.step(AuthStep.Ready); runCurrent()
        assertNull(repo.state.value.profile); assertEquals(ProfileFailure.Unavailable, repo.state.value.failure)
        auth.step(AuthStep.Phone); runCurrent()
        client.profileUpdates.emit(ProfileUpdate.CurrentUser(user)); runCurrent()
        assertFalse(repo.state.value.authenticated); assertNull(repo.state.value.profile)
    }
    @Test fun modelDiagnosticsNeverContainIdentityOrLocalPaths() {
        val privateUser = user.copy(photo = ProfilePhoto(7, "/private/photo"))
        val rendered = listOf(privateUser, privateUser.photo, ProfileState(true, profile = privateUser), ProfileUpdate.CurrentUser(privateUser)).joinToString()
        for (secret in listOf("1234567890123", "Test", "test_handle", "12025550123", "/private/photo")) assertFalse(rendered.contains(secret))
    }
}
