package com.vector.core

import com.vector.core.auth.*
import com.vector.core.media.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MediaRepositoryTest {
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Phone))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
        fun ready(value: Boolean) { state.value = AuthState(if (value) AuthStep.Ready else AuthStep.Phone) }
    }
    private class Client : TelegramFileClient {
        override val updates = MutableSharedFlow<MediaEvent>(extraBufferCapacity = 32)
        val calls = mutableListOf<String>()
        var page = DownloadPage(emptyList(), "")
        var startGate: CompletableDeferred<Unit>? = null
        var pageGate: CompletableDeferred<Unit>? = null
        var fail = false
        var missing = false
        override suspend fun downloads(offset: String): DownloadPage { calls += "list:$offset"; val snapshot = page; pageGate?.await(); return snapshot }
        override suspend fun start(item: MediaItem, managed: Boolean, priority: Int): MediaFile {
            calls += "start:${item.media.file.id}:$managed"; startGate?.await()
            if (fail) error("Fixture failure")
            return item.media.file.copy(active = true, complete = false, path = null)
        }
        override suspend fun pause(id: Int, managed: Boolean) { calls += "pause:$id:$managed" }
        override suspend fun remove(id: Int) { calls += "remove:$id" }
        override suspend fun validate(file: MediaFile) = if (missing) file.copy(complete = false, path = null) else file
        override suspend fun exportFile(item: MediaItem) = validate(item.media.file).takeIf { item.exportAllowed }
    }
    private fun item(id: Int = 1, message: Long = 10, export: Boolean = true) = MediaItem(-100, message,
        TelegramMedia(MediaKind.Photo, MediaFile(id, 1000), "Fixture.jpg", "image/jpeg"), export)
    private fun completed(item: MediaItem) = item.media.file.copy(downloaded = 1000, complete = true, path = "/fixture/private/file")

    @Test fun downloadsOnlyStartAfterAuthenticationAndRestoreNativeIndex() = runTest {
        val auth = Auth(); val client = Client(); val item = item()
        client.page = DownloadPage(listOf(DownloadEntry(item, completed(item), DownloadPhase.Complete)), "next")
        val repo = MediaRepository(auth, client, backgroundScope); runCurrent()
        repo.request(item); runCurrent(); assertTrue(client.calls.isEmpty())
        auth.ready(true); runCurrent()
        assertEquals(DownloadPhase.Complete, repo.state.value.downloads.single().phase)
        assertTrue(repo.state.value.more); assertEquals(listOf("list:"), client.calls)
    }
    @Test fun concurrentRequestsAreDeduplicatedAndProgressIsUpdateDriven() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); client.startGate = CompletableDeferred()
        repeat(5) { repo.request(item()) }; runCurrent()
        assertEquals(1, client.calls.count { it.startsWith("start:") })
        client.startGate!!.complete(Unit); runCurrent()
        client.updates.emit(MediaEvent.File(item().media.file.copy(active = true, downloaded = 250))); runCurrent()
        assertEquals(.25f, repo.state.value.downloads.single().file.progress)
        assertEquals(DownloadPhase.Downloading, repo.state.value.downloads.single().phase)
        client.updates.emit(MediaEvent.File(completed(item()))); runCurrent()
        assertEquals(DownloadPhase.Complete, repo.state.value.downloads.single().phase)
    }
    @Test fun pauseAndResumeUseSameFileAndRemovalNeverDeletesCachedBytes() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); repo.request(item()); runCurrent()
        repo.pause(1); runCurrent(); assertEquals(DownloadPhase.Paused, repo.state.value.downloads.single().phase)
        assertTrue("pause:1:true" in client.calls)
        repo.request(item()); runCurrent(); assertEquals(2, client.calls.count { it == "start:1:true" })
        repo.remove(1); runCurrent(); assertTrue(repo.state.value.downloads.isEmpty()); assertTrue("remove:1" in client.calls)
        assertTrue(client.calls.none { "delete" in it })
    }
    @Test fun failedRequestCanRetryAndUnexpectedStopBecomesFailed() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); client.fail = true; repo.request(item()); runCurrent()
        assertEquals(DownloadPhase.Failed, repo.state.value.downloads.single().phase)
        client.fail = false; repo.request(item()); runCurrent()
        assertEquals(DownloadPhase.Downloading, repo.state.value.downloads.single().phase)
        client.updates.emit(MediaEvent.File(item().media.file)); runCurrent()
        assertEquals(DownloadPhase.Failed, repo.state.value.downloads.single().phase)
    }
    @Test fun completedPreviewsAreReusedAndMissingFilesTriggerDownload() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent()
        val available = item().let { it.copy(media = it.media.copy(file = completed(it))) }
        repeat(3) { repo.request(available, managed = false); runCurrent() }
        assertTrue(client.calls.none { it.startsWith("start:") })
        client.missing = true; repo.request(available, managed = false); runCurrent()
        assertTrue("start:1:false" in client.calls)
    }
    @Test fun protectedMediaIsNeverAddedToDownloadIndexOrExported() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); repo.request(item(export = false)); runCurrent()
        assertTrue("start:1:false" in client.calls); assertTrue(repo.state.value.downloads.isEmpty())
        assertNull(repo.exportFile(item(export = false)))
    }
    @Test fun logoutCancelsPendingDownloadAndClearsAccountMetadata() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); client.startGate = CompletableDeferred(); repo.request(item()); runCurrent()
        val session = repo.state.value.session
        auth.ready(false); runCurrent(); client.startGate!!.complete(Unit); runCurrent()
        assertFalse(repo.state.value.authenticated); assertTrue(repo.state.value.files.isEmpty()); assertTrue(repo.state.value.downloads.isEmpty())
        auth.ready(true); runCurrent(); assertTrue(repo.state.value.session > session); assertTrue(repo.state.value.downloads.isEmpty())
    }
    @Test fun removedUpdateWinsOverInFlightIndexPage() = runTest {
        val auth = Auth(); val client = Client(); val item = item()
        client.page = DownloadPage(listOf(DownloadEntry(item, item.media.file, DownloadPhase.Available)), "")
        client.pageGate = CompletableDeferred()
        val repo = MediaRepository(auth, client, backgroundScope); auth.ready(true); runCurrent()
        client.updates.emit(MediaEvent.Removed(1)); runCurrent()
        client.pageGate!!.complete(Unit); runCurrent()
        assertTrue(repo.state.value.downloads.isEmpty())
    }
    @Test fun completionUpdateWinsOverOlderDownloadRequestResponse() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); client.startGate = CompletableDeferred(); repo.request(item()); runCurrent()
        client.updates.emit(MediaEvent.File(completed(item()))); runCurrent()
        client.startGate!!.complete(Unit); runCurrent()
        assertEquals(DownloadPhase.Complete, repo.state.value.downloads.single().phase)
        assertTrue(repo.state.value.files[1]!!.complete)
    }
    @Test fun explicitDownloadPromotesAnInFlightPreviewWithoutDuplicatePreviewRequests() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent(); client.startGate = CompletableDeferred()
        repo.request(item(), managed = false); runCurrent()
        repeat(3) { repo.request(item()) }; runCurrent()
        client.startGate!!.complete(Unit); runCurrent()
        assertEquals(1, client.calls.count { it == "start:1:false" })
        assertEquals(1, client.calls.count { it == "start:1:true" })
        assertEquals(1, repo.state.value.downloads.size)
    }
    @Test fun previewMetadataCacheIsBounded() = runTest {
        val auth = Auth(); val client = Client(); val repo = MediaRepository(auth, client, backgroundScope)
        auth.ready(true); runCurrent()
        for (id in 1..400) { client.updates.emit(MediaEvent.File(MediaFile(id))); runCurrent() }
        assertTrue(repo.state.value.files.size <= 256)
    }
    @Test fun viewerNavigationIsBoundedAndPinchPanCancelNeverDismiss() {
        val items = listOf(item(1, 10), item(2, 20))
        assertEquals(20L, adjacentMedia(items, 10, 1)?.messageId)
        assertNull(adjacentMedia(items, 10, -1)); assertNull(adjacentMedia(items, 99, 1))
        assertEquals(ViewerAction.Next, viewerRelease(-70f, 2f, 1f, false, false, 1f))
        assertEquals(ViewerAction.Previous, viewerRelease(70f, 2f, 1f, false, false, 1f))
        assertEquals(ViewerAction.Dismiss, viewerRelease(0f, 100f, 1f, false, false, 1f))
        assertEquals(ViewerAction.None, viewerRelease(0f, 100f, 2f, false, false, 1f))
        assertEquals(ViewerAction.None, viewerRelease(0f, 100f, 1f, true, false, 1f))
        assertEquals(ViewerAction.None, viewerRelease(0f, 100f, 1f, false, true, 1f))
        assertEquals(ViewerAction.None, viewerRelease(0f, 100f, 1f, false, false, 2f))
    }
}
