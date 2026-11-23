package com.vector.messenger.feature.avatar

import android.os.SystemClock
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.auth.*
import com.vector.core.profile.*
import com.vector.messenger.feature.media.MediaImages
import com.vector.messenger.feature.media.PhotoUploadStore
import com.vector.messenger.feature.media.AvatarCropArea
import com.vector.messenger.telegram.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

val LocalAvatarViewModel = staticCompositionLocalOf<AvatarViewModel?> { null }
val LocalAccountAction = staticCompositionLocalOf<() -> Unit> { {} }
data class AvatarDisplay(val session: Long, val name: String, val photo: ProfilePhoto?)
data class AvatarCache(val authenticated: Boolean = false, val session: Long = 0,
    val identities: Map<AvatarOwner, AvatarIdentity> = emptyMap(), val files: Map<Int, ProfilePhoto> = emptyMap())
data class AvatarViewerState(val identity: AvatarIdentity, val pictures: List<AvatarPicture> = emptyList(),
    val offset: Int = 0, val from: Long = 0, val more: Boolean = true, val loading: Boolean = false, val error: String? = null, val revision: Int = 0)
data class AvatarEditState(val busy: Boolean = false, val progress: Float? = null, val error: String? = null,
    val uncertain: Boolean = false, val completed: Int = 0)
data class AvatarMembersState(val entries: List<AvatarIdentity> = emptyList(), val offset: Int = 0,
    val more: Boolean = true, val loading: Boolean = false, val notice: String? = null)

@HiltViewModel
class AvatarViewModel @Inject constructor(auth: AuthenticationRepository, private val client: TdlibAvatarClient,
    private val profiles: ProfileRepository, private val store: PhotoUploadStore) : ViewModel() {
    val ownProfile = profiles.state
    private val mutable = MutableStateFlow(AvatarCache())
    val state = mutable.asStateFlow()
    private val viewing = MutableStateFlow<AvatarViewerState?>(null)
    val viewer = viewing.asStateFlow()
    private val editing = MutableStateFlow(AvatarEditState())
    val edit = editing.asStateFlow()
    private val memberState = MutableStateFlow(AvatarMembersState())
    val members = memberState.asStateFlow()
    private var memberJob: Job? = null
    private var scope: CoroutineScope? = null
    private val identities = linkedMapOf<AvatarOwner, AvatarIdentity>()
    private val files = linkedMapOf<Int, ProfilePhoto>()
    private val resolving = mutableSetOf<AvatarOwner>()
    private val resolved = mutableSetOf<AvatarOwner>()
    private val identityRevisions = mutableMapOf<AvatarOwner, Long>()
    private val downloads = mutableSetOf<Int>()
    private val retryAt = mutableMapOf<Int, Long>()
    private val decodeFailures = mutableSetOf<Int>()
    private var viewerJob: Job? = null
    private var historyJob: Job? = null
    private var viewerToken = 0
    private var uploadFile = 0
    private var uploadPath: String? = null
    init {
        viewModelScope.launch {
            auth.state.map { it.step == AuthStep.Ready }.distinctUntilChanged().collectLatest { ready ->
                identities.clear(); files.clear(); resolving.clear(); resolved.clear(); identityRevisions.clear(); downloads.clear(); retryAt.clear(); decodeFailures.clear(); viewerToken++
                viewing.value = null; editing.value = AvatarEditState(); uploadFile = 0; uploadPath = null; MediaImages.clear()
                memberState.value = AvatarMembersState()
                mutable.value = AvatarCache(ready, mutable.value.session + 1)
                if (ready) coroutineScope {
                    scope = this
                    try {
                        client.updates.collect { json ->
                            when (json.optString("@type")) {
                                "updateUser" -> json.optJSONObject("user")?.let { user ->
                                    val id = user.optLong("id")
                                    if (identities.keys.any { it.user && it.id == id } || identities.values.any { it.userId == id } || ownProfile.value.profile?.id == id) {
                                        val identity = withContext(Dispatchers.IO) { client.user(user) }
                                        accept(identity)
                                        identities.values.filter { !it.owner.user && it.userId == id }.toList().forEach {
                                            accept(identity.copy(owner = it.owner, name = it.name, canSave = it.canSave && identity.canSave))
                                        }
                                    }
                                }
                                "updateChatPhoto" -> {
                                    val owner = AvatarOwner(json.optLong("chat_id"), false)
                                    identities[owner]?.let { old ->
                                        val photo = json.optJSONObject("photo")
                                        val small = withContext(Dispatchers.IO) { photo?.optJSONObject("small")?.let(client::file) }
                                        val big = withContext(Dispatchers.IO) { photo?.optJSONObject("big")?.let(client::file) }
                                        accept(old.copy(small = small, big = big, photoId = 0))
                                    }
                                }
                                "updateFile" -> json.optJSONObject("file")?.takeIf { value ->
                                    val id = value.optInt("id")
                                    id == uploadFile || (uploadPath != null && value.optJSONObject("local")?.optString("path") == uploadPath) ||
                                        id in downloads || id in files || identities.values.any { it.small?.fileId == id }
                                }?.let { applyFile(it) }
                            }
                        }
                    } finally { scope = null }
                }
            }
        }
    }
    private fun publish() {
        while (identities.size > 2048) {
            val owner = identities.keys.first(); identities.remove(owner); resolved.remove(owner); identityRevisions.remove(owner)
        }
        while (files.size > 2048) files.remove(files.keys.first())
        mutable.update { it.copy(identities = identities.toMap(), files = files.toMap()) }
    }
    private fun picture(identity: AvatarIdentity): List<AvatarPicture> = identity.big?.let { listOf(AvatarPicture(identity.photoId, identity.small, it)) }.orEmpty()
    private fun accept(identity: AvatarIdentity, refreshViewer: Boolean = true) {
        val old = identities.put(identity.owner, identity)
        identityRevisions[identity.owner] = (identityRevisions[identity.owner] ?: 0) + 1
        identity.small?.takeIf { it.localPath != null }?.let { files[it.fileId] = it }
        publish()
        val current = viewing.value
        if (current?.identity?.owner == identity.owner) {
            if (refreshViewer && old?.small?.fileId != identity.small?.fileId) {
                viewerToken++; viewerJob?.cancel(); historyJob?.cancel()
                viewing.value = AvatarViewerState(identity, picture(identity), revision = current.revision + 1)
                moreHistory()
            } else viewing.value = current.copy(identity = identity)
        }
        identity.small?.takeUnless { it.fileId == ownProfile.value.profile?.photo?.fileId }?.let { download(it.fileId) }
    }
    fun ensure(owner: AvatarOwner, name: String, small: ProfilePhoto?) {
        if (!state.value.authenticated || owner.id == 0L) return
        if (owner !in identities) {
            if (small != null) accept(AvatarIdentity(owner, name, small), false)
        }
        if (owner !in resolved) resolve(owner)
        val identity = identities[owner]
        val target = if (identity != null) identity.small else small
        target?.takeUnless { it.fileId == ownProfile.value.profile?.photo?.fileId }?.let { download(it.fileId) }
    }
    private fun resolve(owner: AvatarOwner) {
        if (!resolving.add(owner)) return
        val revision = identityRevisions[owner]
        scope?.launch {
            try { val identity = client.identity(owner); ensureActive(); resolved += owner; if (identityRevisions[owner] == revision) accept(identity) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Retain initials and allow a viewer refresh to retry. */ }
            finally { resolving.remove(owner) }
        }
    }
    fun observe(owner: AvatarOwner, name: String, fallback: ProfilePhoto?): Flow<AvatarDisplay> = state.map { current ->
        val identity = current.identities[owner]
        val photo = if (!current.authenticated) null else if (identity != null) identity.small else fallback
        AvatarDisplay(current.session, identity?.name?.ifBlank { name } ?: name, photo?.let { current.files[it.fileId] ?: it })
    }.distinctUntilChanged()
    private suspend fun applyFile(json: JSONObject) {
        if (uploadPath != null && json.optJSONObject("local")?.optString("path") == uploadPath) uploadFile = json.optInt("id")
        val photo = withContext(Dispatchers.IO) { client.file(json) } ?: return
        files.remove(photo.fileId); files[photo.fileId] = photo
        val active = json.optJSONObject("local")?.optBoolean("is_downloading_active") == true
        if (!active) {
            downloads.remove(photo.fileId)
            if (photo.localPath == null) retryAt[photo.fileId] = SystemClock.elapsedRealtime() + 30_000 else retryAt.remove(photo.fileId)
        }
        if (photo.fileId == uploadFile) {
            val total = json.optLong("size").takeIf { it > 0 } ?: json.optLong("expected_size")
            val uploaded = json.optJSONObject("remote")?.optLong("uploaded_size") ?: 0
            editing.update { it.copy(progress = if (total > 0) (uploaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat() else null) }
        }
        publish()
    }
    fun download(id: Int, force: Boolean = false) {
        if (!state.value.authenticated || id <= 0 || (!force && files[id]?.localPath != null) || id in downloads ||
            (!force && (retryAt[id] ?: 0) > SystemClock.elapsedRealtime())) return
        downloads += id
        scope?.launch {
            try { applyFile(client.download(id)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { downloads.remove(id); retryAt[id] = SystemClock.elapsedRealtime() + 30_000 }
        }
    }
    fun missingFile(id: Int) {
        if (!decodeFailures.add(id)) return
        files[id] = ProfilePhoto(id); retryAt.remove(id); publish()
        scope?.launch {
            try { client.call(request("deleteFile", "file_id" to id)); ensureActive(); downloads.remove(id); download(id, true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Viewer refresh can retry a no-longer-accessible photo. */ }
        }
    }
    fun decoded(id: Int) { decodeFailures.remove(id) }
    fun isOwn(identity: AvatarIdentity): Boolean = identity.userId != null && identity.userId == ownProfile.value.profile?.id && ownProfile.value.authenticated
    fun open(owner: AvatarOwner, name: String, small: ProfilePhoto?, confirmedRefresh: Boolean = false) {
        if (!state.value.authenticated) return
        viewerJob?.cancel(); historyJob?.cancel(); val token = ++viewerToken
        memberJob?.cancel(); memberState.value = AvatarMembersState()
        val initial = identities[owner] ?: AvatarIdentity(owner, name, small)
        viewing.value = AvatarViewerState(initial, picture(initial), loading = true, revision = token)
        viewerJob = scope?.launch {
            try {
                val identity = client.identity(owner); ensureActive()
                if (token != viewerToken) return@launch
                if (confirmedRefresh) editing.update { it.copy(uncertain = false, error = null) }
                accept(identity, false)
                viewing.value = AvatarViewerState(identity, picture(identity), revision = token)
                identity.big?.let { download(it.fileId) }
                moreHistory()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == viewerToken) viewing.update { it?.copy(loading = false, error = "Unable to load this profile. Refresh when connected.") } }
        }
    }
    fun close() { if (!edit.value.busy) { viewerToken++; viewerJob?.cancel(); historyJob?.cancel(); memberJob?.cancel(); viewing.value = null } }
    fun refresh() { profiles.refresh(); viewer.value?.identity?.let { open(it.owner, it.name, it.small, confirmedRefresh = true) } }
    fun loadMembers() {
        val current = memberState.value
        val identity = viewer.value?.identity ?: return
        if (identity.userId != null || current.loading || !current.more) return
        val token = viewerToken
        memberState.value = current.copy(loading = true, notice = null)
        memberJob = scope?.launch {
            try {
                val page = client.members(identity.owner, current.offset); ensureActive()
                if (token != viewerToken) return@launch
                page.members.forEach { resolved += it.owner; accept(it, false) }
                memberState.value = AvatarMembersState((current.entries + page.members).distinctBy { it.owner }, page.offset, page.more,
                    notice = if (!page.allowed) "Telegram does not allow viewing this member list." else null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == viewerToken) memberState.value = current.copy(notice = "Unable to load members. Try again when connected.") }
        }
    }
    fun moreHistory() {
        val current = viewer.value ?: return
        if (current.loading || !current.more) return
        val token = viewerToken
        viewing.value = current.copy(loading = true, error = null)
        historyJob = scope?.launch {
            try {
                val page = client.history(current.identity, current.offset, current.from); ensureActive()
                if (token != viewerToken) return@launch
                val merged = current.pictures.toMutableList()
                page.photos.forEach { photo ->
                    val index = merged.indexOfFirst { (photo.id != 0L && it.id == photo.id) || it.big.fileId == photo.big.fileId || (it.small != null && it.small.fileId == photo.small?.fileId) }
                    if (index < 0) merged += photo else if (merged[index].id == 0L) merged[index] = photo
                }
                viewing.value = (viewing.value ?: current).copy(pictures = merged, loading = false, offset = page.offset, from = page.fromMessage, more = page.more)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == viewerToken) viewing.update { it?.copy(loading = false, error = "Photo history unavailable. Only photos permitted by Telegram can be shown.") } }
        }
    }
    fun change(uri: String, crop: AvatarCropArea) {
        val identity = viewer.value?.identity ?: return
        if (!isOwn(identity) || edit.value.busy || edit.value.uncertain) return
        val session = state.value.session
        editing.value = editing.value.copy(busy = true, error = null, progress = null)
        scope?.launch {
            var source: com.vector.core.media.UploadPhoto? = null
            var handedOff = false
            try {
                val prepared = store.prepareAvatar(uri, crop)
                source = prepared
                ensureActive()
                if (state.value.session != session || !isOwn(identity)) return@launch
                handedOff = true
                uploadPath = prepared.path
                client.setPhoto(prepared.path); ensureActive()
                profiles.refresh()
                editing.update { it.copy(busy = false, progress = 1f, completed = it.completed + 1) }
                open(identity.owner, identity.name, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { editing.update { it.copy(busy = false, error = "Unable to update the profile photo. Refresh your profile before retrying.",
                uncertain = handedOff && (failure as? com.vector.core.messaging.MessagingException)?.failure == com.vector.core.messaging.MessagingFailure.Network) } }
            catch (_: OutOfMemoryError) { editing.update { it.copy(busy = false, error = "Not enough memory. Try a smaller image.") } }
            finally { uploadFile = 0; uploadPath = null; if (!handedOff) source?.let { withContext(NonCancellable) { store.discard(listOf(it)) } } }
        }
    }
    fun remove(photo: AvatarPicture) {
        val identity = viewer.value?.identity ?: return
        if (!isOwn(identity) || photo.id == 0L || edit.value.busy || viewer.value?.pictures?.none { it.id == photo.id } != false) return
        editing.update { it.copy(busy = true, error = null, progress = null) }
        scope?.launch {
            try {
                client.deletePhoto(photo.id); ensureActive(); profiles.refresh()
                editing.update { it.copy(busy = false, completed = it.completed + 1) }
                open(identity.owner, identity.name, null)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { editing.update { it.copy(busy = false, error = "Unable to remove this profile photo. Refresh and try again.") } }
        }
    }
    suspend fun exportPhoto(photo: AvatarPicture): ProfilePhoto? {
        val identity = viewer.value?.identity ?: return null
        if (viewer.value?.pictures?.none { it.big.fileId == photo.big.fileId } != false) return null
        val fresh = client.identity(identity.owner)
        if (!fresh.canSave || fresh.big == null) return null
        return withContext(Dispatchers.IO) { client.file(client.currentFile(photo.big.fileId)) }?.takeIf { it.localPath != null }
    }
    suspend fun savePhoto(photo: AvatarPicture, destination: String, session: Long, owner: AvatarOwner): Boolean {
        if (state.value.session != session || viewer.value?.identity?.owner != owner) return false
        return try {
            val source = exportPhoto(photo)?.localPath ?: return false
            if (state.value.session != session || viewer.value?.identity?.owner != owner) return false
            store.saveAvatar(source, destination); true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }
}
