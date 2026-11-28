package com.vector.messenger.feature

import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal fun JSONArray.jsonObjects() = (0 until length()).map { getJSONObject(it) }
internal fun jsonList(values: Collection<*>) = JSONArray(values)

internal data class PrivacySetting(val id: String, val title: String, val type: String, val description: String = "", val contactsOnly: Boolean = false)
internal val telegramPrivacy = listOf(
    PrivacySetting("seen", "Last Seen & Online", "userPrivacySettingShowStatus", "Telegram may still show approximate activity or brief online activity. Sharing less also limits what you can see."),
    PrivacySetting("phone", "Who can see my number", "userPrivacySettingShowPhoneNumber"),
    PrivacySetting("discovery", "Who can find me by my number", "userPrivacySettingAllowFindingByPhoneNumber", "People who already know your number may still recognize your account.", true),
    PrivacySetting("photos", "Profile Photos", "userPrivacySettingShowProfilePhoto"),
    PrivacySetting("invites", "Groups & Channels", "userPrivacySettingAllowChatInvites"),
    PrivacySetting("calls", "Who Can Call Me", "userPrivacySettingAllowCalls"),
    PrivacySetting("p2p", "Peer-to-Peer Calls", "userPrivacySettingAllowPeerToPeerCalls", "Restricting direct connections can route supported calls through Telegram infrastructure, protecting your IP address with possible added latency."),
    PrivacySetting("forward", "Forwarded Messages", "userPrivacySettingShowLinkInForwardedMessages", "Controls links to your account on forwarded messages; it does not prevent people from copying your name or messages.")
)

/** Works on complete rule snapshots, retaining unknown Telegram rules and exception ordering. */
internal object PrivacyRules {
    private const val prefix = "userPrivacySettingRule"
    val baseTypes = setOf(prefix + "AllowAll", prefix + "AllowContacts", prefix + "RestrictAll")
    fun equivalent(first: String, second: String): Boolean {
        fun normalized(raw: String): String {
            val values = JSONArray(raw).jsonObjects().toMutableList()
            if (values.lastOrNull()?.optString("@type") == prefix + "RestrictAll") values.removeAt(values.lastIndex)
            return canonicalJson(JSONArray(values))
        }
        return normalized(first) == normalized(second)
    }
    fun audience(raw: String): String {
        val types = JSONArray(raw).jsonObjects().map { it.optString("@type") }.filter { it in baseTypes }
        return when (types) {
            listOf(prefix + "AllowAll") -> "Everybody"
            listOf(prefix + "AllowContacts"), listOf(prefix + "AllowContacts", prefix + "RestrictAll") -> "My Contacts"
            listOf(prefix + "RestrictAll"), emptyList<String>() -> "Nobody"
            else -> "Custom rules"
        }
    }
    fun audience(raw: String, label: String): JSONArray = JSONArray().apply {
        JSONArray(raw).jsonObjects().filter { it.optString("@type") !in baseTypes }.forEach(::put)
        when (label) {
            "Everybody" -> put(request(prefix + "AllowAll"))
            "My Contacts" -> { put(request(prefix + "AllowContacts")); put(request(prefix + "RestrictAll")) }
            "Nobody" -> put(request(prefix + "RestrictAll"))
            else -> error("Unsupported audience")
        }
    }
    fun exception(raw: String, allow: Boolean, chat: Boolean, id: Long, add: Boolean): JSONArray {
        val own = prefix + (if (allow) "Allow" else "Restrict") + (if (chat) "ChatMembers" else "Users")
        val opposite = prefix + (if (allow) "Restrict" else "Allow") + (if (chat) "ChatMembers" else "Users")
        val field = if (chat) "chat_ids" else "user_ids"
        val rules = JSONArray(raw).jsonObjects()
        if (add && rules.any { it.optString("@type") == opposite && it.getJSONArray(field).let { ids -> (0 until ids.length()).any { ids.getLong(it) == id } } })
            throw SettingsFailure("Remove this account from the opposite exception list first.")
        var exists = false
        val output = JSONArray()
        rules.forEach { rule ->
            if (rule.optString("@type") == own) {
                val ids = rule.getJSONArray(field).let { values -> (0 until values.length()).map { values.getLong(it) } }.toMutableList()
                if (add && !exists && id !in ids) ids.add(id)
                if (!add) ids.removeAll { it == id }
                exists = true
                if (ids.isNotEmpty()) output.put(JSONObject(rule.toString()).put(field, jsonList(ids)))
            } else output.put(rule)
        }
        if (add && !exists) return JSONArray().put(request(own, field to JSONArray().put(id))).apply { output.jsonObjects().forEach(::put) }
        return output
    }
}

internal class SettingsFailure(message: String) : Exception(message)
data class BlockedAccount(val id: Long, val user: Boolean, val name: String)
data class TelegramSettingsState(
    val account: Long = 0, val busy: Boolean = false, val error: String? = null,
    val privacy: Map<String, String> = emptyMap(), val blocked: List<BlockedAccount> = emptyList(),
    val blockedOffset: Int = 0, val blockedTotal: Int? = null, val contacts: List<BlockedAccount> = emptyList(),
    val sessions: String? = null, val password: String? = null, val recovery: String? = null,
    val notifications: Map<String, String> = emptyMap(), val downloads: String? = null,
    val peers: Map<String, BlockedAccount> = emptyMap(),
)

/** One TDLib client, account-bound RPCs, server-confirmed state; no security credentials are persisted. */
@Singleton
class TelegramSettingsRepository @Inject constructor(private val profile: ProfileRepository,
    private val manager: TdlibClientManager, private val scope: CoroutineScope, private val chats: com.vector.core.ChatRepository) {
    val groups = chats.chats.map { list -> list.filter { it.kind == com.vector.core.ChatKind.Group }.map { BlockedAccount(it.id, false, it.title) } }
    private val mutable = MutableStateFlow(TelegramSettingsState())
    val state = mutable.asStateFlow()
    private val mutex = Mutex()
    private var generation = 0L
    private val downloadGuard = Mutex()
    private var downloadsFetchedAt = 0L
    init {
        scope.launch {
            profile.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } ?: 0L }.distinctUntilChanged().collect { id ->
                generation++; downloadsFetchedAt = 0; mutable.value = TelegramSettingsState(account = id)
            }
        }
    }
    private suspend fun rpc(id: Long, type: String, vararg fields: Pair<String, Any?>): JSONObject {
        val result = manager.messagingRequest(request(type, *fields), id)
        if (result.optString("@type") == "error") {
            // Never surface raw server errors: some may contain supplied security input.
            val message = result.optString("message")
            throw SettingsFailure(when {
                message.startsWith("FLOOD_WAIT") || result.optInt("code") == 429 -> "Telegram asks you to wait before trying again."
                message.contains("SESSION_TOO_FRESH") || message.contains("PASSWORD_TOO_FRESH") -> "Telegram does not allow this action from a recently authorized session. Try later from an established device."
                message.contains("PASSWORD_HASH_INVALID") -> "The current password is incorrect."
                message.contains("EMAIL_INVALID") -> "Telegram did not accept this email address."
                message.contains("CODE_INVALID") || message.contains("CODE_EXPIRED") -> "The code is invalid or expired. Request a new code."
                else -> "Telegram could not confirm this change. Refresh and try again when connected."
            })
        }
        result.remove("@extra")
        return result
    }
    private fun operation(action: suspend (Long) -> Unit) {
        if (state.value.busy || state.value.account == 0L) return
        val id = state.value.account; val token = generation
        mutable.update { it.copy(busy = true, error = null) }
        scope.launch {
            mutex.withLock {
                try {
                    if (generation == token) action(id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) {
                    if (generation == token) mutable.update { it.copy(error = (failure as? SettingsFailure)?.message ?: "Unable to load Telegram settings. Retry when connected.") }
                } finally { if (generation == token) mutable.update { it.copy(busy = false) } }
            }
        }
    }
    private fun update(id: Long, change: (TelegramSettingsState) -> TelegramSettingsState) {
        if (state.value.account == id && profile.state.value.authenticated && profile.state.value.profile?.id == id) mutable.update(change)
    }
    fun dismissError() { mutable.update { it.copy(error = null) } }
    private suspend fun rules(id: Long, setting: PrivacySetting) = rpc(id, "getUserPrivacySettingRules", "setting" to request(setting.type)).getJSONArray("rules")
    fun refresh(page: String) = operation { id ->
        when {
            page.startsWith("tg_privacy_") -> {
                val keys = when (val key = page.removePrefix("tg_privacy_")) { "phone" -> listOf("phone", "discovery"); else -> listOf(key) }
                for (setting in telegramPrivacy.filter { it.id in keys }) {
                    val raw = canonicalJson(rules(id, setting))
                    update(id) { it.copy(privacy = it.privacy + (setting.id to raw)) }
                    for (rule in JSONArray(raw).jsonObjects()) {
                        val users = rule.optJSONArray("user_ids"); val groups = rule.optJSONArray("chat_ids")
                        val values = users ?: groups ?: continue
                        for (index in 0 until values.length()) {
                            val peer = resolve(id, request(if (users != null) "messageSenderUser" else "messageSenderChat", (if (users != null) "user_id" else "chat_id") to values.getLong(index)))
                            update(id) { it.copy(peers = it.peers + ("${peer.user}:${peer.id}" to peer)) }
                        }
                    }
                }
            }
            page == "tg_blocked" -> loadBlocked(id, false)
            page == "devices" -> loadSessions(id)
            page == "tg_password" -> loadPassword(id)
            page == "tg_notifications" -> for (key in notificationScopes) loadNotifications(id, key)
            page == "tg_downloads" -> loadDownloads(id)
        }
    }
    internal fun setAudience(key: String, expected: String, label: String) = editPrivacy(key, expected) { PrivacyRules.audience(it, label) }
    internal fun setException(key: String, expected: String, allow: Boolean, chat: Boolean, peer: Long, add: Boolean) = editPrivacy(key, expected) { PrivacyRules.exception(it, allow, chat, peer, add) }
    private fun editPrivacy(key: String, expected: String, edit: (String) -> JSONArray) = operation { id ->
        val setting = telegramPrivacy.first { it.id == key }
        val current = canonicalJson(rules(id, setting))
        update(id) { it.copy(privacy = it.privacy + (key to current)) }
        if (current != expected) throw SettingsFailure("These rules changed on Telegram. Review the refreshed values and try again.")
        val merged = edit(current)
        rpc(id, "setUserPrivacySettingRules", "setting" to request(setting.type), "rules" to request("userPrivacySettingRules", "rules" to merged))
        val confirmed = canonicalJson(rules(id, setting))
        update(id) { it.copy(privacy = it.privacy + (key to confirmed)) }
        if (!PrivacyRules.equivalent(confirmed, merged.toString())) throw SettingsFailure("Telegram returned adjusted rules. Showing its confirmed settings.")
    }
    private suspend fun resolve(id: Long, sender: JSONObject): BlockedAccount {
        val user = sender.optString("@type") == "messageSenderUser"
        val peer = sender.getLong(if (user) "user_id" else "chat_id")
        val raw = rpc(id, if (user) "getUser" else "getChat", (if (user) "user_id" else "chat_id") to peer)
        val name = if (user) listOf(raw.optString("first_name"), raw.optString("last_name")).filter(String::isNotBlank).joinToString(" ") else raw.optString("title")
        return BlockedAccount(peer, user, name.ifBlank { if (user) "Deleted account" else "Unavailable chat" })
    }
    private suspend fun loadBlocked(id: Long, more: Boolean) {
        val offset = if (more) state.value.blockedOffset else 0
        val result = rpc(id, "getBlockedMessageSenders", "block_list" to request("blockListMain"), "offset" to offset, "limit" to 50)
        val raw = result.getJSONArray("senders").jsonObjects()
        val accounts = raw.map { resolve(id, it) }
        update(id) { it.copy(blocked = ((if (more) it.blocked else emptyList()) + accounts).distinctBy { account -> account.user to account.id },
            blockedOffset = offset + raw.size, blockedTotal = if (raw.isEmpty()) offset else result.getInt("total_count")) }
    }
    fun moreBlocked() = operation { loadBlocked(it, true) }
    fun loadContacts() = operation { id ->
        val ids = rpc(id, "getContacts").getJSONArray("user_ids")
        val contacts = (0 until ids.length()).map { resolve(id, request("messageSenderUser", "user_id" to ids.getLong(it))) }
        update(id) { it.copy(contacts = contacts.sortedBy { contact -> contact.name.lowercase() }) }
    }
    fun block(peer: BlockedAccount, block: Boolean) = operation { id ->
        rpc(id, "setMessageSenderBlockList", "sender_id" to request(if (peer.user) "messageSenderUser" else "messageSenderChat", (if (peer.user) "user_id" else "chat_id") to peer.id),
            "block_list" to if (block) request("blockListMain") else null)
        loadBlocked(id, false)
    }
    private suspend fun loadSessions(id: Long): JSONObject = rpc(id, "getActiveSessions").also { result -> update(id) { it.copy(sessions = result.toString()) } }
    fun terminate(session: Long?) = operation { id ->
        val current = loadSessions(id).getJSONArray("sessions").jsonObjects()
        if (session == null) rpc(id, "terminateAllOtherSessions")
        else {
            val selected = current.firstOrNull { it.getLong("id") == session } ?: throw SettingsFailure("This session has already ended.")
            if (selected.getBoolean("is_current")) throw SettingsFailure("The current device cannot be terminated here.")
            rpc(id, "terminateSession", "session_id" to session)
        }
        loadSessions(id)
    }
    private suspend fun loadPassword(id: Long) { val result = rpc(id, "getPasswordState"); update(id) { it.copy(password = result.toString()) } }
    internal fun passwordAction(type: String, fields: List<Pair<String, Any?>> = emptyList(), onConfirmed: () -> Unit = {}) = operation { id ->
        val allowed = setOf("setPassword", "setRecoveryEmailAddress", "checkRecoveryEmailAddressCode", "resendRecoveryEmailAddressCode", "cancelRecoveryEmailAddressVerification", "requestPasswordRecovery", "recoverPassword")
        require(type in allowed)
        val result = rpc(id, type, *fields.toTypedArray())
        if (type == "requestPasswordRecovery") update(id) { it.copy(recovery = result.toString()) }
        else {
            loadPassword(id)
            if (type == "recoverPassword") update(id) { it.copy(recovery = null) }
        }
        if (state.value.account == id) onConfirmed()
    }
    private suspend fun loadNotifications(id: Long, key: String): JSONObject = rpc(id, "getScopeNotificationSettings", "scope" to request(key)).also { value -> update(id) { it.copy(notifications = it.notifications + (key to canonicalJson(value))) } }
    internal fun notification(key: String, expected: String, field: String, value: Any) = operation { id ->
        require(field in setOf("mute_for", "show_preview"))
        val current = loadNotifications(id, key)
        if (canonicalJson(current) != expected) throw SettingsFailure("Notification settings changed. Review and try again.")
        current.put(field, value)
        rpc(id, "setScopeNotificationSettings", "scope" to request(key), "notification_settings" to current)
        loadNotifications(id, key)
    }
    private suspend fun loadDownloads(id: Long): JSONObject = rpc(id, "getAutoDownloadSettingsPresets").also { value ->
        update(id) { downloadsFetchedAt = android.os.SystemClock.elapsedRealtime(); it.copy(downloads = canonicalJson(value)) }
    }
    internal suspend fun allowAutomatic(item: com.vector.core.media.MediaItem, context: android.content.Context): Boolean = downloadGuard.withLock {
        val id = state.value.account
        if (id == 0L || item.media.file.size <= 0 || item.media.file.uploading || !item.media.file.canDownload) return@withLock false
        try {
            if (state.value.downloads == null || android.os.SystemClock.elapsedRealtime() - downloadsFetchedAt > 30_000) loadDownloads(id)
            if (id != state.value.account) return@withLock false
            val connectivity = context.getSystemService(android.net.ConnectivityManager::class.java)
            val caps = connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities) ?: return@withLock false
            if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@withLock false
            val slot = when {
                !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING) -> "low"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED) -> "high"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "medium"
                else -> "low" // Unknown/VPN transport uses the conservative roaming policy.
            }
            val policy = JSONObject(state.value.downloads!!).getJSONObject(slot)
            val field = when (item.media.kind) {
                com.vector.core.media.MediaKind.Photo -> "max_photo_file_size"
                com.vector.core.media.MediaKind.Video, com.vector.core.media.MediaKind.VideoNote, com.vector.core.media.MediaKind.Animation -> "max_video_file_size"
                else -> "max_other_file_size"
            }
            policy.getBoolean("is_auto_download_enabled") && item.media.file.size <= policy.getLong(field)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false } // No confirmed policy means no automatic original-file transfer.
    }
    internal fun download(network: String, expected: String, field: String, value: Any) = operation { id ->
        require(field in setOf("is_auto_download_enabled", "max_photo_file_size", "max_video_file_size", "max_other_file_size"))
        val presets = loadDownloads(id)
        val slot = when (network) { "networkTypeMobileRoaming" -> "low"; "networkTypeWiFi" -> "high"; else -> "medium" }
        val current = presets.getJSONObject(slot)
        if (canonicalJson(current) != expected) throw SettingsFailure("Download settings changed. Review and try again.")
        current.put(field, value)
        rpc(id, "setAutoDownloadSettings", "settings" to current, "type" to request(network))
        loadDownloads(id)
    }
    fun clearThumbnails(onConfirmed: () -> Unit) = operation { id ->
        rpc(id, "optimizeStorage", "size" to 0L, "ttl" to 0, "count" to 0, "immunity_delay" to 3600,
            "file_types" to JSONArray().put(request("fileTypeThumbnail")), "chat_ids" to JSONArray(), "exclude_chat_ids" to JSONArray(), "return_deleted_file_statistics" to true, "chat_limit" to 0)
        if (state.value.account == id) onConfirmed()
    }
    companion object { val notificationScopes = listOf("notificationSettingsScopePrivateChats", "notificationSettingsScopeGroupChats", "notificationSettingsScopeChannelChats") }
}
