package com.vector.messenger.feature.profile

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class ProfileModuleState(val details: EditableProfile? = null, val loading: Boolean = false,
    val saving: Boolean = false, val posting: Boolean = false, val error: String? = null, val saved: Int = 0, val posted: Int = 0)

@HiltViewModel
internal class ProfileModuleViewModel @Inject constructor(private val profile: ProfileRepository,
    private val client: TdlibProfileEditor, private val storyClient: TdlibStoryClient, val stories: TelegramStories, private val saved: SavedStateHandle,
    @ApplicationContext private val context: Context) : ViewModel() {
    val account = profile.state
    private val mutable = MutableStateFlow(ProfileModuleState())
    val state = mutable.asStateFlow()
    val identities = connectedProfileIdentities(listOf(ConnectedProfileSource(TelegramProfileIdentityAdapter.Provider,
        combine(account, state) { owner, profile -> TelegramProfileIdentityAdapter.connected(owner, profile.details) }.distinctUntilChanged())))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val displayedProfile = saved.getStateFlow("displayedProfile", "")
    fun displayProfile(key: ProfileIdentityKey) {
        if (identities.value.any { it.key == key }) saved["displayedProfile"] = key.saveKey
    }
    fun ensureProfile(key: ProfileIdentityKey) {
        if (key.providerId != TelegramProfileIdentityAdapter.Provider || key.accountId != account.value.profile?.id?.toString()) return
        if (state.value.details?.account != account.value.profile?.id && !state.value.loading) reload()
        if (stories.state.value.chat == 0L && !stories.state.value.loading && stories.state.value.error == null) stories.refresh()
    }
    val storyState = stories.state
    val posts = stories.state.map { it.posts }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val visibility = saved.getStateFlow("storyVisibility", "")
    val selectedUsers = saved.getStateFlow("storyUsers", arrayListOf<Long>())
    val first = saved.getStateFlow("first", "")
    val last = saved.getStateFlow("last", "")
    val username = saved.getStateFlow("username", "")
    val bio = saved.getStateFlow("bio", "")
    val text = saved.getStateFlow("postText", "")
    val media = saved.getStateFlow("postMedia", arrayListOf<String>())
    private val contactList = MutableStateFlow<List<AvatarIdentity>>(emptyList())
    val contacts = contactList.asStateFlow()
    val contactQuery = saved.getStateFlow("storyContactQuery", "")
    private val contactsError = MutableStateFlow<String?>(null)
    val contactError = contactsError.asStateFlow()
    fun searchContacts(value: String) { saved["storyContactQuery"] = value }
    private var epoch = 0L
    private var loadJob: Job? = null
    private var saveJob: Job? = null
    private var postJob: Job? = null
    private var bioRevision = 0L
    init {
        viewModelScope.launch {
            combine(visibility, contactQuery, account) { mode, query, owner -> Triple(mode, query, owner.profile?.id?.takeIf { owner.authenticated }) }
                .distinctUntilChanged().collectLatest { (mode, query, id) ->
                    contactList.value = emptyList(); contactsError.value = null
                    if (mode != "Selected" || id == null) return@collectLatest
                    delay(250)
                    try { contactList.value = storyClient.contacts(query) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { contactsError.value = "Unable to load Telegram contacts. Change the search to retry." }
                }
        }
        viewModelScope.launch {
            account.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged().collectLatest { id ->
                epoch++; loadJob?.cancel(); saveJob?.cancel(); postJob?.cancel()
                mutable.value = ProfileModuleState()
                if (id == null) return@collectLatest
                if (saved.get<Long>("draftAccount") != id) {
                    listOf("first", "last", "username", "bio", "postText").forEach { saved[it] = "" }
                    saved["postMedia"] = arrayListOf<String>(); saved["storyVisibility"] = ""; saved["storyUsers"] = arrayListOf<Long>(); saved["editReady"] = false; saved["draftAccount"] = id
                }
                reload()

            }
        }
        viewModelScope.launch {
            stories.state.map { Triple(it.account, it.posting, it.posted) }.distinctUntilChanged().collect { (owner, posting, posted) ->
                if (owner == id()) {
                    if (posted > state.value.posted) {
                        saved["postText"] = ""; saved["postMedia"] = arrayListOf<String>()
                        saved["storyVisibility"] = ""; saved["storyUsers"] = arrayListOf<Long>()
                    }
                    mutable.update { it.copy(posting = posting, posted = posted) }
                }
            }
        }
        viewModelScope.launch {
            client.updates.collect { update ->
                if (update.optString("@type") == "updateUser") {
                    val user = update.optJSONObject("user")
                    if (account.value.authenticated && user != null && user.optLong("id") == account.value.profile?.id) {
                        mutable.update { value -> value.copy(details = value.details?.copy(first = user.safeText("first_name"),
                            last = user.safeText("last_name"), username = user.optJSONObject("usernames")?.safeText("editable_username").orEmpty())) }
                    }
                }
                if (update.optString("@type") == "updateUserFullInfo" && update.optLong("user_id") == account.value.profile?.id && account.value.authenticated) {
                    bioRevision++
                    update.optJSONObject("user_full_info")?.let { full ->
                        mutable.update { it.copy(details = it.details?.copy(bio = client.bio(full))) }
                    }
                }
            }
        }
    }
    private fun id() = account.value.profile?.id?.takeIf { account.value.authenticated }
    fun reload() {
        val id = id() ?: return
        if (state.value.saving) return
        loadJob?.cancel()
        val session = epoch
        val version = bioRevision
        mutable.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                var details = client.load(id)
                ensureActive()
                if (session != epoch) return@launch
                if (version != bioRevision) state.value.details?.let { details = details.copy(bio = it.bio) }
                mutable.update { it.copy(details = details, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (session == epoch) mutable.update { it.copy(loading = false, error = "Unable to load the full Telegram profile. Check your connection and refresh.") } }
        }
    }
    fun beginEdit(reset: Boolean = false) {
        val details = state.value.details ?: return
        if (!reset && saved.get<Boolean>("editReady") == true && saved.get<Boolean>("editDirty") == true) return
        saved["first"] = details.first; saved["last"] = details.last; saved["username"] = details.username; saved["bio"] = details.bio
        saved["editReady"] = true; saved["editDirty"] = false
        listOf("first", "last", "username", "bio").forEach { saved["dirty_$it"] = false }
    }
    fun field(key: String, value: String) { if (!state.value.saving && key in setOf("first", "last", "username", "bio")) { saved[key] = value; saved["editDirty"] = true; saved["dirty_$key"] = true } }
    fun postText(value: String) { if (!state.value.posting) saved["postText"] = value }
    fun selectMedia(values: List<String>) {
        if (id() == null || state.value.posting) return
        val retained = media.value.toMutableList()
        for (value in values.distinct()) {
            if (retained.size >= 1) break
            val uri = Uri.parse(value)
            if (uri.scheme != "content") continue
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                if (value !in retained) retained += value
            } catch (_: Exception) { mutable.update { it.copy(error = "Access to this media could not be retained. Select it from a local document provider.") } }
        }
        saved["postMedia"] = ArrayList(retained)
    }
    fun removeMedia(uri: String) { if (!state.value.posting) saved["postMedia"] = ArrayList(media.value.filterNot { it == uri }) }
    fun saveProfile() {
        val original = state.value.details ?: return
        if (state.value.saving || state.value.loading) return
        val draft = original.copy(
            first = if (saved.get<Boolean>("dirty_first") == true) first.value.trim() else original.first,
            last = if (saved.get<Boolean>("dirty_last") == true) last.value.trim() else original.last,
            username = if (saved.get<Boolean>("dirty_username") == true) username.value.trim().removePrefix("@") else original.username,
            bio = if (saved.get<Boolean>("dirty_bio") == true) bio.value else original.bio)
        fun length(value: String) = value.codePointCount(0, value.length)
        val invalid = when {
            length(draft.first) !in 1..64 || length(draft.last) > 64 || (draft.first + draft.last).any { it == '\n' || it == '\r' } -> "Use a first name of 1–64 characters and a last name of up to 64 characters."
            draft.username != original.username && draft.username.isNotEmpty() && !draft.username.matches(Regex("[A-Za-z][A-Za-z0-9_]{4,31}")) -> "Use a username of 5–32 letters, digits or underscores, starting with a letter."
            length(draft.bio) > draft.bioLimit || draft.bio.any { it == '\n' || it == '\r' } -> "Telegram allows up to ${draft.bioLimit} biography characters without line breaks."
            else -> null
        }
        if (invalid != null) { mutable.update { it.copy(error = invalid) }; return }
        val session = epoch
        loadJob?.cancel()
        mutable.update { it.copy(saving = true, error = null) }
        saveJob = viewModelScope.launch {
            try {
                val confirmed = client.save(original, draft) { session == epoch && id() == draft.account }
                ensureActive()
                if (session != epoch) return@launch
                mutable.update { it.copy(details = confirmed, saving = false, saved = it.saved + 1) }
                beginEdit(reset = true); profile.refresh()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (session == epoch) {
                    mutable.update { it.copy(saving = false, error = "${(failure as? ProfileEditFailure)?.explanation ?: "Unable to update Telegram."} Some fields may already have changed; refresh to verify.") }
                    profile.refresh()
                }
            }
        }
    }
    fun visibility(value: String) { if (!state.value.posting && value in listOf("Everyone", "Contacts", "Selected")) saved["storyVisibility"] = value }
    fun toggleUser(id: Long) {
        if (state.value.posting || id <= 0) return
        saved["storyUsers"] = ArrayList(selectedUsers.value.let { if (id in it) it - id else it + id })
    }
    fun publish() {
        if (id() == null || state.value.posting) return
        val source = media.value.singleOrNull()
        if (source == null) { mutable.update { it.copy(error = "Select one photo or video for a Telegram Story. Text-only Stories are not supported.") }; return }
        if (visibility.value.isBlank() || visibility.value == "Selected" && selectedUsers.value.isEmpty()) {
            mutable.update { it.copy(error = "Choose Story visibility and any selected contacts before publishing.") }; return
        }
        mutable.update { it.copy(error = null) }
        stories.publish(source, text.value, visibility.value, selectedUsers.value)
    }
    fun delete(post: TelegramPost, delete: Boolean, done: () -> Unit) = stories.remove(post, delete, done)
}
