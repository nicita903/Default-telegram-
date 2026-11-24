package com.vector.messenger.calling

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Build capability, not a simulated call state. No engine means no call may be created/accepted. */
object TelegramCallingCapability {
    const val explanation = "Voice calling uses the source-built Telegram media engine when available on this device. Video calling is not supported in this phase."
}

data class TelegramCallRecord(val message: Long, val chat: Long, val date: Long, val outgoing: Boolean,
    val video: Boolean, val seconds: Int, val outcome: String) {
    val description: String get() {
        val direction = if (outgoing) "Outgoing" else "Incoming"
        val kind = if (video) "video call" else "voice call"
        return "$direction $kind" + if (outcome.isNotEmpty()) " · $outcome" else ""
    }
    override fun toString() = "TelegramCallRecord([redacted])"
}
internal fun mapTelegramCall(json: JSONObject): TelegramCallRecord? {
    val content = json.optJSONObject("content") ?: return null
    if (content.optString("@type") != "messageCall" || com.vector.messenger.telegram.restricted(json) ||
        com.vector.messenger.telegram.restricted(content) || json.optJSONObject("self_destruct_type") != null ||
        json.optJSONObject("ephemeral_content") != null) return null
    val reason = content.optJSONObject("discard_reason")?.optString("@type")
    val seconds = content.optInt("duration").coerceAtLeast(0)
    val outgoing = json.optBoolean("is_outgoing")
    val outcome = when (reason) {
        "callDiscardReasonMissed" -> if (outgoing) "Unanswered" else "Missed"
        "callDiscardReasonDeclined" -> "Declined"
        "callDiscardReasonDisconnected" -> "Disconnected"
        "callDiscardReasonUpgradeToGroupCall" -> "Continued as group call"
        else -> ""
    }
    return TelegramCallRecord(json.optLong("id"), json.optLong("chat_id"), json.optLong("date"), outgoing,
        content.optBoolean("is_video"), seconds, outcome)
}

/** Uses the existing authenticated JSON transport. SearchCallMessages has a global string cursor. */
class TelegramCallHistoryRepository @Inject constructor(private val client: TdlibAvatarClient) {
    suspend fun page(account: Long, chat: Long, cursor: String): Pair<List<TelegramCallRecord>, String> = withContext(Dispatchers.IO) {
        val result = client.call(request("searchCallMessages", "offset" to cursor, "limit" to 50, "only_missed" to false), account)
        val rows = result.optJSONArray("messages")?.objects().orEmpty().filter { it.optLong("chat_id") == chat }.mapNotNull(::mapTelegramCall)
        rows to result.optString("next_offset")
    }
}
data class CallHistoryState(val chat: Long = 0, val records: List<TelegramCallRecord> = emptyList(),
    val cursor: String = "", val loaded: Boolean = false, val more: Boolean = true, val loading: Boolean = false, val error: String? = null)
val LocalCallHistory = staticCompositionLocalOf<CallHistoryViewModel?> { null }

@HiltViewModel
class CallHistoryViewModel @Inject constructor(private val repository: TelegramCallHistoryRepository,
    private val account: ProfileRepository, private val library: ContactLibraryRepository) : ViewModel() {
    private val mutable = MutableStateFlow(CallHistoryState())
    val state = mutable.asStateFlow()
    private var owner = 0L
    private var epoch = 0L
    private var work: Job? = null
    private var updates: Job? = null
    private val removed = mutableSetOf<Long>()
    init { viewModelScope.launch {
        account.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } }.distinctUntilChanged().collect { id ->
            if (owner != 0L && owner != id) close()
        }
    } }
    fun open(chat: Long) {
        close()
        owner = account.state.value.profile?.id?.takeIf { account.state.value.authenticated } ?: return
        mutable.value = CallHistoryState(chat = chat)
        loadMore()
        updates = viewModelScope.launch { library.updates.collect { update ->
            when (update.optString("@type")) {
                "updateDeleteMessages" -> if (update.optLong("chat_id") == chat) {
                    val ids = update.optJSONArray("message_ids") ?: return@collect
                    removed.addAll((0 until ids.length()).map { ids.optLong(it) })
                    mutable.update { it.copy(records = it.records.filterNot { row -> row.message in removed }) }
                }
                "updateNewMessage" -> update.optJSONObject("message")?.takeIf { it.optLong("chat_id") == chat }?.let(::mapTelegramCall)?.let { record ->
                    if (record.message !in removed) mutable.update { it.copy(records = (listOf(record) + it.records).distinctBy { row -> row.message }
                        .sortedWith(compareByDescending<TelegramCallRecord> { row -> row.date }.thenByDescending { row -> row.message })) }
                }
            }
        } }
    }
    fun loadMore() {
        val old = state.value
        if (owner == 0L || old.chat == 0L || old.loading || !old.more) return
        val token = epoch; val accountId = owner
        mutable.value = old.copy(loading = true, error = null)
        work = viewModelScope.launch {
            try {
                val (records, cursor) = repository.page(accountId, old.chat, old.cursor)
                if (token != epoch) return@launch
                mutable.update { it.copy(records = (it.records + records.filterNot { row -> row.message in removed }).distinctBy { row -> row.message }
                    .sortedWith(compareByDescending<TelegramCallRecord> { row -> row.date }.thenByDescending { row -> row.message }),
                    cursor = cursor, loaded = true, more = cursor.isNotEmpty() && cursor != old.cursor, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (token == epoch) mutable.update { it.copy(loading = false, error = "Unable to load Telegram call history. Retry when connected.") } }
        }
    }
    fun close() { epoch++; work?.cancel(); updates?.cancel(); removed.clear(); owner = 0; mutable.value = CallHistoryState() }
}
