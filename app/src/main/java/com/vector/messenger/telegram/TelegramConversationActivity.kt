package com.vector.messenger.telegram

import com.vector.core.*
import com.vector.core.profile.ProfileRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import org.json.JSONObject

@Singleton
class TelegramConversationActivity @Inject constructor(private val manager: TdlibClientManager, private val account: ProfileRepository, scope: CoroutineScope) {
    private data class Activity(val sender: String, val kind: ChatActivityKind, val at: Long)
    private val statuses = linkedMapOf<Long, ContactPresence>()
    private val names = linkedMapOf<String, String>()
    private val activities = mutableMapOf<Long, MutableMap<String, Activity>>()
    private val expiry = mutableMapOf<String, Job>()
    private val resolved = mutableSetOf<String>()
    private val resolving = mutableSetOf<String>()
    private val watchedUsers = mutableSetOf<Long>()
    private val revisions = mutableMapOf<Long, Long>()
    private val changed = MutableStateFlow(0L)
    private var owner = 0L
    private var session: CoroutineScope? = null
    init { scope.launch {
        account.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } }.distinctUntilChanged().collectLatest { id ->
            owner = id ?: 0; statuses.clear(); names.clear(); activities.clear(); expiry.clear(); resolved.clear(); resolving.clear(); watchedUsers.clear(); revisions.clear(); publish()
            if (id == null) return@collectLatest
            coroutineScope {
                session = this
                try { manager.activityUpdates.collect { update -> reduce(update) } }
                finally { session = null }
            }
        }
    } }
    private fun publish() {
        while (statuses.size > 2048) { val id = statuses.keys.first(); statuses.remove(id); revisions.remove(id); resolved.remove("u$id") }
        while (names.size > 2048) { val key = names.keys.first(); names.remove(key); resolved.remove(key) }
        changed.update { it + 1 }
    }
    private fun presence(user: Long, value: JSONObject) {
        revisions[user] = (revisions[user] ?: 0) + 1
        val kind = when (value.optString("@type")) {
            "userStatusOnline" -> PresenceKind.Online; "userStatusOffline" -> PresenceKind.Offline
            "userStatusRecently" -> PresenceKind.Recently; "userStatusLastWeek" -> PresenceKind.WithinWeek
            "userStatusLastMonth" -> PresenceKind.WithinMonth; else -> PresenceKind.Unknown
        }
        val expires = value.optLong("expires").takeIf { it > 0 }
        statuses[user] = ContactPresence(ConversationIdentity("telegram", owner.toString(), user.toString()),
            if (kind == PresenceKind.Online && (expires ?: 0) <= System.currentTimeMillis() / 1000) PresenceKind.Unknown else kind,
            value.optLong("was_online").takeIf { it > 0 }, expires)
        expiry.remove("online:$user")?.cancel()
        if (kind == PresenceKind.Online && expires != null) expiry["online:$user"] = session!!.launch {
            delay((expires * 1000 - System.currentTimeMillis()).coerceAtLeast(1))
            statuses[user]?.takeIf { it.kind == PresenceKind.Online && it.expires == expires }?.let { statuses[user] = it.copy(kind = PresenceKind.Unknown); publish() }
            expiry.remove("online:$user")
        }
        publish()
    }
    private fun resolve(id: Long, user: Boolean) {
        val key = "${if (user) "u" else "c"}$id"
        if (user) watchedUsers += id
        val scope = session ?: return
        if (key in resolved || !resolving.add(key)) return
        val version = revisions[id]
        scope.launch {
            try {
                val json = manager.messagingRequest(if (user) request("getUser", "user_id" to id) else request("getChat", "chat_id" to id))
                ensureActive()
                if (json.optString("@type") == "error") return@launch
                names[key] = if (user) listOf(json.safeText("first_name"), json.safeText("last_name")).filter(String::isNotBlank).joinToString(" ") else json.safeText("title")
                resolved += key
                if (user && revisions[id] == version) json.optJSONObject("status")?.let { presence(id, it) }
                publish()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Another subscription can retry; no presence is fabricated. */ }
            finally { resolving.remove(key) }
        }
    }
    fun ensure(peer: Long?) { peer?.takeIf { it > 0 }?.let { resolve(it, true) } }
    private fun clear(chat: Long, sender: String) {
        activities[chat]?.remove(sender)
        if (activities[chat]?.isEmpty() == true) activities.remove(chat)
        expiry.remove("$chat:$sender")?.cancel(); publish()
    }
    private fun reduce(update: JSONObject) {
        when (update.optString("@type")) {
            "updateUserStatus" -> if (update.optLong("user_id") in watchedUsers) update.optJSONObject("status")?.let { presence(update.optLong("user_id"), it) }
            "updateUser" -> update.optJSONObject("user")?.let { user ->
                val id = user.optLong("id"); if (id !in watchedUsers) return@let
                names["u$id"] = listOf(user.safeText("first_name"), user.safeText("last_name")).filter(String::isNotBlank).joinToString(" ")
                user.optJSONObject("status")?.let { presence(id, it) }; publish()
            }
            "updateNewMessage" -> update.optJSONObject("message")?.let { message ->
                val sender = message.optJSONObject("sender_id")
                val key = if (sender?.optString("@type") == "messageSenderUser") "u${sender.optLong("user_id")}" else "c${sender?.optLong("chat_id") ?: 0}"
                clear(message.optLong("chat_id"), key)
            }
            "updateChatAction" -> {
                if (update.optJSONObject("topic_id") != null) return
                val sender = update.optJSONObject("sender_id") ?: return
                val user = sender.optString("@type") == "messageSenderUser"
                val id = sender.optLong(if (user) "user_id" else "chat_id")
                if (user && id == owner || id == 0L) return
                val chat = update.optLong("chat_id"); val key = "${if (user) "u" else "c"}$id"
                val kind = when (update.optJSONObject("action")?.optString("@type")) {
                    "chatActionTyping" -> ChatActivityKind.Typing; "chatActionRecordingVoiceNote" -> ChatActivityKind.RecordingVoice
                    "chatActionRecordingVideo", "chatActionRecordingVideoNote" -> ChatActivityKind.RecordingVideo
                    "chatActionUploadingPhoto" -> ChatActivityKind.UploadingPhoto; "chatActionUploadingVideo" -> ChatActivityKind.UploadingVideo
                    "chatActionUploadingDocument" -> ChatActivityKind.UploadingDocument; "chatActionUploadingVoiceNote" -> ChatActivityKind.UploadingVoice
                    "chatActionUploadingVideoNote" -> ChatActivityKind.UploadingVideoNote; "chatActionChoosingSticker" -> ChatActivityKind.ChoosingSticker
                    "chatActionChoosingLocation" -> ChatActivityKind.ChoosingLocation; "chatActionChoosingContact" -> ChatActivityKind.ChoosingContact
                    "chatActionStartPlayingGame" -> ChatActivityKind.PlayingGame; else -> null
                }
                if (kind == null) { clear(chat, key); return }
                resolve(id, user)
                activities.getOrPut(chat) { linkedMapOf() }[key] = Activity(key, kind, System.currentTimeMillis())
                expiry.remove("$chat:$key")?.cancel()
                expiry["$chat:$key"] = session!!.launch { delay(6000); clear(chat, key) }
                publish()
            }
        }
    }
    fun observe(chat: Long, peer: Long?, kind: ChatKind): Flow<ConversationSubtitle> = changed.map {
        if (owner == 0L) return@map ConversationSubtitle()
        val active = activities[chat]?.values?.sortedByDescending { it.at }.orEmpty()
        if (active.isNotEmpty()) {
            val activity = active.first().kind
            val phrase = when (activity) {
                ChatActivityKind.Typing -> "typing"; ChatActivityKind.RecordingVoice -> "recording voice"
                ChatActivityKind.RecordingVideo -> "recording video"; ChatActivityKind.UploadingPhoto -> "uploading photo"
                ChatActivityKind.UploadingVideo -> "uploading video"; ChatActivityKind.UploadingDocument -> "uploading file"
                ChatActivityKind.UploadingVoice -> "uploading voice"; ChatActivityKind.UploadingVideoNote -> "uploading video note"
                ChatActivityKind.ChoosingSticker -> "choosing a sticker"; ChatActivityKind.ChoosingLocation -> "choosing a location"
                ChatActivityKind.ChoosingContact -> "choosing a contact"; ChatActivityKind.PlayingGame -> "playing a game"
            }
            val text = if (kind == ChatKind.Contact) phrase else if (active.size == 1) "${names[active.first().sender]?.takeIf(String::isNotBlank) ?: "Someone"} is $phrase"
                else if (active.all { it.kind == ChatActivityKind.Typing }) "${active.size} people typing" else "${active.size} people active"
            ConversationSubtitle(text, activity = true)
        } else {
            val value = peer?.let { statuses[it] }
            val text = when (value?.kind) {
                PresenceKind.Online -> "online"
                PresenceKind.Offline -> value.wasOnline?.let { "last seen ${DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(it))}" } ?: "last seen unavailable"
                PresenceKind.Recently -> "last seen recently"; PresenceKind.WithinWeek -> "last seen within a week"
                PresenceKind.WithinMonth -> "last seen within a month"; else -> if (kind == ChatKind.Group) "group" else if (kind == ChatKind.Channel) "channel" else "last seen unavailable"
            }
            ConversationSubtitle(text, online = value?.kind == PresenceKind.Online, presence = value)
        }
    }.distinctUntilChanged()
}
