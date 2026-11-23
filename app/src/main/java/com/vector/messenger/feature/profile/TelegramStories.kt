package com.vector.messenger.feature.profile

import android.content.Context
import com.vector.core.profile.*
import com.vector.messenger.telegram.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

internal data class StoryState(val account: Long = 0, val chat: Long = 0, val posts: List<TelegramPost> = emptyList(),
    val files: Map<Int, ProfilePhoto> = emptyMap(), val loading: Boolean = false, val error: String? = null,
    val more: Boolean = true, val posting: Boolean = false, val publishError: String? = null, val uncertain: Boolean = false,
    val posted: Int = 0, val progress: Float? = null)

@Singleton
internal class TelegramStories @Inject constructor(private val account: ProfileRepository, private val client: TdlibStoryClient,
    private val uploads: StoryUploadStore, scope: CoroutineScope, @ApplicationContext context: Context) {
    private val mutable = MutableStateFlow(StoryState())
    val state = mutable.asStateFlow()
    private var sessionScope: CoroutineScope? = null
    private var epoch = 0L
    private var loadingJob: Job? = null
    private var cursor = 0
    private val records = linkedMapOf<Int, TelegramPost>()
    private val versions = mutableMapOf<Int, Long>()
    private var revision = 0L
    private val files = linkedMapOf<Int, ProfilePhoto>()
    private val fileVersions = mutableMapOf<Int, Long>()
    private val downloads = mutableSetOf<Int>()
    private val retry = mutableMapOf<Int, Long>()
    private var viewingId = 0
    private var pendingId = 0
    private var sourcePath: String? = null
    private val confirmedIds = linkedSetOf<Int>()
    private val pendingResults = linkedMapOf<Int, JSONObject>()
    private val markers = context.getSharedPreferences("telegram_story_pending_v1", Context.MODE_PRIVATE)
    init {
        scope.launch {
            account.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged().collectLatest { id ->
                epoch++; loadingJob = null; cursor = 0; records.clear(); versions.clear(); files.clear(); fileVersions.clear(); downloads.clear(); retry.clear(); pendingResults.clear(); confirmedIds.clear(); sourcePath = null; viewingId = 0
                mutable.value = StoryState(account = id ?: 0)
                if (id == null) return@collectLatest
                pendingId = markers.getInt(id.toString(), 0)
                if (markers.contains(id.toString())) mutable.update { it.copy(uncertain = true, publishError = "A previous Story may still be posting. Check its status before publishing again.") }
                coroutineScope {
                    sessionScope = this
                    try {
                        launch { client.updates.collect { update -> handle(update) } }
                        launch {
                            client.connection.collect { connection ->
                                if (connection == ProfileConnection.Ready) {
                                    if (state.value.error != null && !state.value.loading) refresh()
                                    records.values.mapNotNull { it.thumbnail?.fileId ?: if (!it.video) it.file?.fileId else null }.distinct().forEach { download(it, force = true) }
                                }
                            }
                        }
                        refresh()
                        awaitCancellation()
                    } finally { sessionScope = null }
                }
            }
        }
    }
    private fun publishRecords() { mutable.update { it.copy(posts = records.values.filter { post -> post.posted && !post.pending }.sortedWith(compareByDescending<TelegramPost> { it.date }.thenByDescending { it.id }), files = files.toMap()) } }
    private fun apply(post: TelegramPost) {
        if (post.chat != state.value.chat) return
        versions[post.id] = ++revision
        if (post.posted && !post.pending) records[post.id] = post else records.remove(post.id)
        listOfNotNull(post.file, post.thumbnail).filter { it.localPath != null }.forEach { files[it.fileId] = it }
        publishRecords()
    }
    private suspend fun handle(update: JSONObject) {
        when (update.optString("@type")) {
            "updateFile" -> update.optJSONObject("file")?.let { value ->
                val id = value.optInt("id")
                if (id in downloads || id in files || records.values.any { it.file?.fileId == id || it.thumbnail?.fileId == id }) applyFile(value)
                if (sourcePath != null && value.optJSONObject("local")?.optString("path") == sourcePath) {
                    val total = value.optLong("size").takeIf { it > 0 } ?: value.optLong("expected_size")
                    val sent = value.optJSONObject("remote")?.optLong("uploaded_size") ?: 0
                    mutable.update { it.copy(progress = if (total > 0) (sent.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null) }
                }
            }
            "updateStory", "updateStoryPostSucceeded", "updateStoryPostFailed" -> {
                val json = update.optJSONObject("story") ?: return
                if (json.optLong("poster_chat_id") != state.value.chat) return
                val post = withContext(Dispatchers.IO) { client.map(json) }
                if (update.optString("@type") != "updateStoryPostFailed") apply(post)
                if (update.optString("@type") != "updateStory") {
                    val old = if (update.optString("@type") == "updateStoryPostSucceeded") update.optInt("old_story_id") else post.id
                    pendingResults[old] = update
                    while (pendingResults.size > 32) pendingResults.remove(pendingResults.keys.first())
                    if (pendingId == old) finish(update)
                }
            }
            "updateStoryDeleted" -> if (update.optLong("story_poster_chat_id") == state.value.chat) {
                val id = update.optInt("story_id"); versions[id] = ++revision; records.remove(id); publishRecords()
                if (id == pendingId) { clearPending(); mutable.update { it.copy(posting = false, uncertain = false, publishError = "Story posting was canceled.") } }
            }
            "updateChatActiveStories" -> if (update.optJSONObject("active_stories")?.optLong("chat_id") == state.value.chat && !state.value.loading) refresh()
        }
    }
    private fun clearPending() { markers.edit().remove(state.value.account.toString()).apply(); pendingId = 0; sourcePath = null }
    private suspend fun finish(event: JSONObject) {
        if (event.optString("@type") == "updateStoryPostSucceeded") {
            val post = withContext(Dispatchers.IO) { client.map(event.getJSONObject("story")) }
            if (post.pending || !post.posted) { mutable.update { it.copy(posting = false, uncertain = true, publishError = "Telegram posted the Story, but its profile status needs verification. Refresh before retrying.") }; return }
            if (!confirmedIds.add(post.id)) return
            while (confirmedIds.size > 256) confirmedIds.remove(confirmedIds.first())
            apply(post); clearPending()
            mutable.update { it.copy(posting = false, uncertain = false, publishError = null, progress = null, posted = it.posted + 1) }
            refresh()
        } else {
            val error = event.optJSONObject("error_type")?.let(client::restriction) ?: client.error(event.optJSONObject("error") ?: JSONObject())
            clearPending(); mutable.update { it.copy(posting = false, uncertain = false, publishError = error, progress = null) }
        }
    }
    fun refresh(more: Boolean = false) {
        val current = state.value
        val owner = current.account.takeIf { it > 0 } ?: return
        if (more && (current.loading || !current.more)) return
        val session = epoch; val from = if (more) cursor else 0; val started = revision
        loadingJob?.cancel()
        mutable.update { it.copy(loading = true, error = null) }
        loadingJob = sessionScope?.launch {
            try {
                val chat = state.value.chat.takeIf { it != 0L } ?: client.selfChat(owner)
                ensureActive(); if (epoch != session) return@launch
                mutable.update { it.copy(chat = chat) }
                val result = client.list(chat, from)
                val raw = result.optJSONArray("stories")?.objects().orEmpty()
                val page = withContext(Dispatchers.IO) { raw.map(client::map) }
                ensureActive(); if (epoch != session) return@launch
                if (!more) records.keys.toList().filter { (versions[it] ?: 0) <= started }.forEach(records::remove)
                page.filter { (versions[it.id] ?: 0) <= started }.forEach { post ->
                    if (post.chat == chat && post.posted && !post.pending) records[post.id] = post
                    listOfNotNull(post.file, post.thumbnail).filter { it.localPath != null }.forEach { files[it.fileId] = it }
                }
                val next = raw.lastOrNull()?.optInt("id") ?: 0
                cursor = next
                publishRecords()
                mutable.update { it.copy(loading = false, more = raw.isNotEmpty() && next != from && records.size < result.optInt("total_count")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { if (epoch == session) mutable.update { it.copy(loading = false, error = (failure as? StoryFailure)?.description ?: "Unable to load Telegram profile Stories. Check connectivity and retry.") } }
        }
    }
    private suspend fun applyFile(json: JSONObject, expected: Long? = null) {
        val photo = withContext(Dispatchers.IO) { client.file(json) } ?: return
        if (expected != null && (fileVersions[photo.fileId] ?: 0) != expected) return
        fileVersions[photo.fileId] = (fileVersions[photo.fileId] ?: 0) + 1
        files[photo.fileId] = photo
        if (json.optJSONObject("local")?.optBoolean("is_downloading_active") != true) {
            downloads.remove(photo.fileId)
            if (photo.localPath == null) retry[photo.fileId] = android.os.SystemClock.elapsedRealtime() + 30_000 else retry.remove(photo.fileId)
        }
        while (files.size > 1024) { val id = files.keys.first(); files.remove(id); fileVersions.remove(id) }
        publishRecords()
    }
    fun download(id: Int, force: Boolean = false) {
        if (id <= 0 || files[id]?.localPath != null || id in downloads || !force && (retry[id] ?: 0) > android.os.SystemClock.elapsedRealtime()) return
        val scope = sessionScope ?: return
        val version = fileVersions[id] ?: 0
        downloads += id
        scope.launch {
            try { applyFile(client.download(id), version) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { downloads.remove(id); retry[id] = android.os.SystemClock.elapsedRealtime() + 30_000 }
        }
    }
    fun open(id: Int) {
        val chat = state.value.chat
        if (chat == 0L) return
        viewingId = id
        val started = versions[id] ?: 0
        sessionScope?.launch {
            try {
                val post = withContext(Dispatchers.IO) { client.map(client.get(chat, id)) }
                if (viewingId != id) return@launch
                client.call(request("openStory", "story_poster_chat_id" to chat, "story_id" to id))
                if ((versions[id] ?: 0) == started) apply(post)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "This Telegram Story is unavailable. Refresh the profile.") } }
        }
    }
    fun close(id: Int) {
        if (viewingId == id) viewingId = 0
        val chat = state.value.chat
        sessionScope?.launch {
            try { client.call(request("closeStory", "story_poster_chat_id" to chat, "story_id" to id)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Closing a viewer never blocks navigation. */ }
        }
    }
    fun publish(uri: String, caption: String, visibility: String, selected: List<Long>) {
        if (state.value.posting || state.value.uncertain || state.value.account <= 0) return
        val scope = sessionScope ?: return
        val owner = state.value.account
        mutable.update { it.copy(posting = true, publishError = null, progress = null) }
        scope.launch {
            var prepared: PreparedStory? = null; var handedOff = false; var accepted = false
            try {
                val chat = state.value.chat.takeIf { it != 0L } ?: client.selfChat(owner)
                mutable.update { it.copy(chat = chat) }
                client.restriction(client.call(request("canPostStory", "chat_id" to chat)))?.let { throw StoryFailure(it) }
                val limit = client.call(request("getOption", "name" to "story_caption_length_max")).exactLong("value")
                if (caption.codePointCount(0, caption.length) > limit) throw StoryFailure("Your Telegram account allows at most $limit Story caption characters.")
                if (visibility !in listOf("Everyone", "Contacts", "Selected") || visibility == "Selected" && selected.isEmpty()) throw StoryFailure("Choose visibility and any selected contacts before posting.")
                val source = uploads.prepare(uri); prepared = source
                ensureActive()
                sourcePath = source.path
                markers.edit().putInt(owner.toString(), 0).apply()
                handedOff = true
                val result = client.publish(chat, source.content, caption, visibility, selected)
                ensureActive(); accepted = true
                pendingId = result.getInt("id")
                markers.edit().putInt(owner.toString(), pendingId).apply()
                val early = pendingResults.remove(pendingId)
                if (early != null) finish(early)
                else if (!result.optBoolean("is_being_posted")) {
                    val verified = client.get(chat, pendingId)
                    if (!verified.optBoolean("is_being_posted") && verified.optBoolean("is_posted_to_chat_page"))
                        finish(request("updateStoryPostSucceeded", "story" to verified))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val uncertain = handedOff && (accepted || failure !is StoryFailure || failure.uncertain)
                if (!uncertain) clearPending()
                mutable.update { it.copy(posting = false, uncertain = uncertain,
                    publishError = (failure as? StoryFailure)?.description ?: if (handedOff) "Posting status is unknown. Check Telegram before trying again." else "Unable to prepare this media. Check local access and choose a supported file.", progress = null) }
            } catch (_: OutOfMemoryError) { clearPending(); mutable.update { it.copy(posting = false, publishError = "Not enough memory to prepare this Story.") } }
            finally { if (!handedOff) prepared?.let { withContext(NonCancellable) { uploads.discard(it) } } }
        }
    }
    fun checkPending() {
        refresh()
        if (pendingId == 0) return
        sessionScope?.launch {
            try {
                val value = client.get(state.value.chat, pendingId)
                if (!value.optBoolean("is_being_posted") && value.optBoolean("is_posted_to_chat_page")) finish(request("updateStoryPostSucceeded", "story" to value))
                else mutable.update { it.copy(publishError = "Telegram is still posting this Story. Wait for confirmation.") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(posting = false, uncertain = true, publishError = "Publication cannot be confirmed yet. Check Telegram before allowing another post.") } }
        }
    }
    fun acknowledgeUnknown() { if (!state.value.posting && state.value.uncertain) { clearPending(); mutable.update { it.copy(uncertain = false, publishError = null) } } }
    fun remove(post: TelegramPost, delete: Boolean, done: () -> Unit) {
        if (post.chat != state.value.chat || state.value.posting) return
        mutable.update { it.copy(posting = true, error = null) }
        sessionScope?.launch {
            try {
                client.remove(post.chat, post.id, delete)
                versions[post.id] = ++revision; records.remove(post.id); publishRecords()
                mutable.update { it.copy(posting = false) }; refresh(); done()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutable.update { it.copy(posting = false, error = (failure as? StoryFailure)?.description ?: "Unable to change this Story. Check connectivity and retry.") } }
        }
    }
}
