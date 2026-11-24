package com.vector.messenger.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.*
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

val LocalContactProfileViewModel = androidx.compose.runtime.staticCompositionLocalOf<ContactProfileViewModel?> { null }

data class ContactCategoryState(val entries: List<SharedEntry> = emptyList(), val next: Long = 0,
    val loaded: Boolean = false, val loading: Boolean = false, val more: Boolean = true, val error: String? = null)
data class ContactProfileState(val chat: Long = 0, val details: ContactDetails? = null,
    val pages: Map<SharedCategory, ContactCategoryState> = emptyMap(), val loading: Boolean = false,
    val busy: Boolean = false, val error: String? = null)

@HiltViewModel
class ContactProfileViewModel @Inject constructor(private val repository: ContactLibraryRepository,
    private val account: ProfileRepository, private val activity: TelegramConversationActivity) : ViewModel() {
    private val mutable = MutableStateFlow(ContactProfileState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var owner = 0L
    private var detailsJob: Job? = null
    private val jobs = mutableMapOf<SharedCategory, Job>()
    private var changes: Job? = null
    private val removed = mutableSetOf<Long>()
    private val revisions = mutableMapOf<Long, Long>()
    val presence = state.map { it.details }.distinctUntilChanged().flatMapLatest { details ->
        if (details == null) flowOf(ConversationSubtitle()) else {
            activity.ensure(details.user); activity.observe(details.chat, details.user, details.kind)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ConversationSubtitle())
    init { viewModelScope.launch {
        account.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged().collect { id ->
            if (owner != 0L && owner != id) close()
        }
    } }
    fun open(chat: Long) {
        if (state.value.chat == chat) return
        close()
        owner = account.state.value.profile?.id?.takeIf { account.state.value.authenticated } ?: return
        mutable.value = ContactProfileState(chat = chat)
        refresh()
        listOf(SharedCategory.Media, SharedCategory.Circles, SharedCategory.Music, SharedCategory.Files, SharedCategory.Links, SharedCategory.Voice).forEach { load(it, preview = true) }
        changes = viewModelScope.launch { repository.updates.collect { update ->
            when (update.optString("@type")) {
                "updateDeleteMessages" -> if (update.optLong("chat_id") == chat) {
                    val ids = update.optJSONArray("message_ids") ?: return@collect
                    val deleted = (0 until ids.length()).map { ids.optLong(it) }.toSet(); removed.addAll(deleted)
                    mutable.update { value -> value.copy(pages = value.pages.mapValues { (_, page) -> page.copy(entries = page.entries.filterNot { it.message.id in deleted }) }) }
                }
                "updateMessageContent" -> if (update.optLong("chat_id") == chat) reloadMessage(update.optLong("message_id"))
                "updateNewMessage" -> update.optJSONObject("message")?.takeIf { it.optLong("chat_id") == chat }?.let { reloadMessage(it.optLong("id")) }
                "updateUser", "updateUserFullInfo" -> {
                    val user = update.optJSONObject("user")?.optLong("id") ?: update.optLong("user_id")
                    if (user == state.value.details?.user) mutable.update { value ->
                        value.copy(details = value.details?.let { repository.updated(it, update) })
                    }
                }
                "updateChatNotificationSettings" -> if (update.optLong("chat_id") == chat) refresh()
            }
        } }
    }
    fun refresh() {
        detailsJob?.cancel(); val token = generation; val chat = state.value.chat; val accountId = owner
        if (chat == 0L || accountId == 0L) return
        mutable.update { it.copy(loading = true, error = null) }
        detailsJob = viewModelScope.launch {
            try { val details = repository.details(chat, accountId)
                if (token == generation) mutable.update { it.copy(details = details, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(loading = false, error = "Unable to load Telegram profile. Retry when connected.") } }
        }
    }
    fun mute() {
        val details = state.value.details ?: return
        if (state.value.busy) return
        val token = generation; val accountId = owner
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try { val verified = repository.mute(details.chat, accountId, !details.muted)
                if (token == generation) mutable.update { it.copy(details = verified, busy = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutable.update { it.copy(busy = false, error = "Telegram could not confirm the notification change.") } }
        }
    }
    fun load(category: SharedCategory, preview: Boolean = false) {
        if (category == SharedCategory.All || owner == 0L || state.value.chat == 0L) return
        val page = state.value.pages[category] ?: ContactCategoryState()
        if (page.loading || page.loaded && !page.more || preview && page.loaded) return
        val token = generation; val chat = state.value.chat; val accountId = owner
        val startedRevisions = revisions.toMap()
        mutable.update { it.copy(pages = it.pages + (category to page.copy(loading = true, error = null))) }
        jobs[category] = viewModelScope.launch {
            try {
                val result = repository.page(chat, accountId, category, page.next, if (preview) 6 else 36)
                if (token != generation) return@launch
                mutable.update { value ->
                    val current = value.pages[category] ?: page
                    val fresh = result.entries.filter { it.message.id !in removed && startedRevisions[it.message.id] == revisions[it.message.id] }
                    val entries = (current.entries + fresh).distinctBy { it.message.id }.sortedWith(compareByDescending<SharedEntry> { it.message.date }.thenByDescending { it.message.id })
                    value.copy(pages = value.pages + (category to ContactCategoryState(entries, result.next, true, false,
                        result.next != 0L && result.next != page.next)))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == generation) mutable.update { value -> value.copy(pages = value.pages +
                (category to (value.pages[category] ?: page).copy(loading = false, error = "Unable to load shared history. Retry."))) } }
        }
    }
    private fun reloadMessage(id: Long) {
        val token = generation; val accountId = owner; val chat = state.value.chat
        val revision = (revisions[id] ?: 0) + 1; revisions[id] = revision
        viewModelScope.launch {
            try {
                val entry = repository.message(chat, accountId, id)
                if (token != generation || revisions[id] != revision || id in removed) return@launch
                mutable.update { value -> value.copy(pages = value.pages.mapValues { (category, page) ->
                    val without = page.entries.filterNot { it.message.id == id }
                    val kind = entry?.message?.media?.kind
                    val matches = when (category) {
                        SharedCategory.Media -> kind in listOf(com.vector.core.media.MediaKind.Photo, com.vector.core.media.MediaKind.Video)
                        SharedCategory.Circles -> kind == com.vector.core.media.MediaKind.VideoNote
                        SharedCategory.Files -> kind == com.vector.core.media.MediaKind.Document
                        SharedCategory.Music -> kind == com.vector.core.media.MediaKind.Audio
                        SharedCategory.Voice -> kind == com.vector.core.media.MediaKind.Voice
                        SharedCategory.GIFs -> kind == com.vector.core.media.MediaKind.Animation
                        SharedCategory.Links -> !entry?.links.isNullOrEmpty()
                        else -> false
                    }
                    page.copy(entries = (without + if (matches && entry != null) listOf(entry) else emptyList())
                        .sortedWith(compareByDescending<SharedEntry> { it.message.date }.thenByDescending { it.message.id }))
                }) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Leave verified history visible until a retry succeeds. */ }
        }
    }
    fun close() {
        generation++; detailsJob?.cancel(); jobs.values.forEach { it.cancel() }; jobs.clear(); changes?.cancel()
        removed.clear(); revisions.clear(); owner = 0; mutable.value = ContactProfileState()
    }
}
