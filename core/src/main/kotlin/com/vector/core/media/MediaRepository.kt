package com.vector.core.media

import com.vector.core.auth.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class MediaState(
    val authenticated: Boolean = false, val session: Long = 0,
    val files: Map<Int, MediaFile> = emptyMap(), val downloads: List<DownloadEntry> = emptyList(),
    val loading: Boolean = false, val more: Boolean = false, val error: String? = null,
)

/** One file reducer over the shared TDLib client. Native download index owns persistence. */
class MediaRepository(auth: AuthenticationRepository, private val client: TelegramFileClient, scope: CoroutineScope) {
    private val mutable = MutableStateFlow(MediaState())
    val state = mutable.asStateFlow()
    private val files = linkedMapOf<Int, MediaFile>()
    private val entries = linkedMapOf<Int, DownloadEntry>()
    private val jobs = mutableMapOf<Int, Job>()
    private val promotions = mutableMapOf<Int, MediaItem>()
    private val touched = mutableSetOf<Int>()
    private val fileRevisions = mutableMapOf<Int, Long>()
    private var revision = 0L
    private var sessionScope: CoroutineScope? = null
    private var offset = ""
    private var pageJob: Job? = null
    suspend fun exportFile(item: MediaItem): MediaFile? {
        val session = state.value.session
        if (!state.value.authenticated || !item.exportAllowed) return null
        val file = client.exportFile(item)
        currentCoroutineContext().ensureActive()
        return file.takeIf { state.value.authenticated && state.value.session == session }
    }
    init {
        scope.launch {
            auth.state.map { it.step == AuthStep.Ready }.distinctUntilChanged().collectLatest { ready ->
                files.clear(); entries.clear(); jobs.clear(); promotions.clear(); touched.clear(); fileRevisions.clear(); revision = 0; offset = ""; pageJob = null
                mutable.value = MediaState(authenticated = ready, session = mutable.value.session + 1)
                if (ready) coroutineScope {
                    sessionScope = this
                    try {
                        launch(start = CoroutineStart.UNDISPATCHED) { client.updates.collect(::reduce) }
                        loadMore()
                        awaitCancellation()
                    } finally { sessionScope = null }
                }
            }
        }
    }
    private fun publish() {
        // Native index is paged; temporary previews have a separate bounded metadata cache.
        while (files.size > entries.size + 256) {
            val id = files.keys.firstOrNull { it !in entries } ?: break
            files.remove(id)
            fileRevisions.remove(id)
        }
        mutable.update { it.copy(files = files.toMap(), downloads = entries.values.toList()) }
    }
    private fun apply(file: MediaFile) {
        files.remove(file.id)
        files[file.id] = file
        entries[file.id]?.let { previous ->
            val phase = when {
                file.complete && file.path != null -> DownloadPhase.Complete
                previous.phase == DownloadPhase.Paused -> DownloadPhase.Paused
                file.active -> DownloadPhase.Downloading
                previous.file.active -> DownloadPhase.Failed
                previous.phase == DownloadPhase.Complete -> DownloadPhase.Available
                else -> previous.phase
            }
            entries[file.id] = previous.copy(file = file, phase = phase,
                error = if (phase == DownloadPhase.Failed) "Download interrupted. Retry when connected." else previous.error.takeIf { phase == DownloadPhase.Failed })
        }
    }
    private fun reduce(event: MediaEvent) {
        when (event) {
            is MediaEvent.File -> { fileRevisions[event.value.id] = ++revision; apply(event.value) }
            is MediaEvent.Added -> {
                val id = event.entry.file.id; touched += id
                if (event.entry.item.exportAllowed) {
                    entries[id] = event.entry.copy(file = files[id] ?: event.entry.file)
                    apply(files[id] ?: event.entry.file)
                }
            }
            is MediaEvent.Paused -> {
                touched += event.id
                entries[event.id]?.let { previous -> entries[event.id] = previous.copy(
                    phase = if (previous.file.complete) DownloadPhase.Complete else if (event.paused) DownloadPhase.Paused else DownloadPhase.Queued, error = null) }
            }
            is MediaEvent.Removed -> { touched += event.id; entries.remove(event.id) }
        }
        publish()
    }
    fun loadMore() {
        val currentScope = sessionScope ?: return
        if (pageJob?.isActive == true || (offset.isEmpty() && entries.size >= 500) || (offset.isNotEmpty() && !state.value.more)) return
        pageJob = currentScope.launch(start = CoroutineStart.LAZY) {
            mutable.update { it.copy(loading = true, error = null) }
            try {
                val page = client.downloads(offset); ensureActive()
                page.entries.filter { it.item.exportAllowed }.forEach { entry ->
                    val id = entry.file.id
                    if (id !in touched) {
                        entries.putIfAbsent(id, entry.copy(file = files[id] ?: entry.file))
                        if (id !in files) files[id] = entry.file
                    }
                }
                offset = page.nextOffset
                mutable.update { it.copy(more = offset.isNotEmpty() && entries.size < 500) }; publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Unable to load downloads. Retry.") } }
            finally { mutable.update { it.copy(loading = false) } }
        }
        pageJob?.start()
    }
    fun request(item: MediaItem, managed: Boolean = true, priority: Int = 16) {
        val currentScope = sessionScope ?: return
        val id = item.media.file.id
        if (id <= 0) return
        if (jobs[id]?.isActive == true) {
            if (managed && item.exportAllowed && id !in entries) promotions[id] = item
            return
        }
        val listed = managed && item.exportAllowed
        mutable.update { it.copy(error = null) }
        val known = files[id] ?: item.media.file
        if (!listed && known.active) return
        if (listed && id in entries && entries[id]?.phase in listOf(DownloadPhase.Downloading, DownloadPhase.Queued)) return
        files.putIfAbsent(id, known)
        if (listed) entries[id] = DownloadEntry(item, known, DownloadPhase.Queued)
        publish()
        jobs[id] = currentScope.launch(start = CoroutineStart.LAZY) {
            val before = fileRevisions[id] ?: 0L
            try {
                val available = client.validate(known); ensureActive()
                if (available.complete && available.path != null && (!listed || id in touched)) {
                    if ((fileRevisions[id] ?: 0L) == before) apply(available)
                }
                else {
                    val result = client.start(item, listed, priority.coerceIn(1, 32)); ensureActive()
                    if ((fileRevisions[id] ?: 0L) == before) apply(result)
                }
                ensureActive(); publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                entries[id]?.let { entries[id] = it.copy(phase = DownloadPhase.Failed, error = "Unable to download. Retry when connected.") }
                mutable.update { it.copy(error = "Unable to download this file.") }; publish()
            } finally {
                if (jobs[id] === currentCoroutineContext()[Job]) {
                    jobs.remove(id)
                    val promote = promotions.remove(id)
                    if (promote != null && isActive) request(promote)
                }
            }
        }
        jobs[id]?.start()
    }
    fun pause(id: Int) = command(id) {
        val managed = id in entries
        client.pause(id, managed)
        currentCoroutineContext().ensureActive()
        entries[id]?.let { entries[id] = it.copy(phase = DownloadPhase.Paused, error = null) }
        files[id]?.let { files[id] = it.copy(active = false) }; publish()
    }
    /** Removes native list metadata only. Never invokes deleteFile or delete_from_cache=true. */
    fun remove(id: Int) = command(id) { client.remove(id); currentCoroutineContext().ensureActive(); touched += id; entries.remove(id); publish() }
    private fun command(id: Int, operation: suspend () -> Unit) {
        val currentScope = sessionScope ?: return
        mutable.update { it.copy(error = null) }
        promotions.remove(id)
        jobs.remove(id)?.cancel()
        jobs[id] = currentScope.launch(start = CoroutineStart.LAZY) {
            try { operation(); ensureActive() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Download action failed. Retry.") } }
            finally { if (jobs[id] === currentCoroutineContext()[Job]) jobs.remove(id) }
        }
        jobs[id]?.start()
    }
}
