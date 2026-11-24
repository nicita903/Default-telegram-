package com.vector.messenger.telegram

import com.vector.core.*
import com.vector.core.media.*
import com.vector.core.profile.ProfilePhoto
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class SharedCategory(val label: String, val filter: String = "") {
    All("All"), Media("Media", "PhotoAndVideo"), Circles("Circles", "VideoNote"), Files("Files", "Document"),
    Links("Links", "Url"), Music("Music", "Audio"), Voice("Voice", "VoiceNote"), GIFs("GIFs", "Animation")
}
data class ContactDetails(val chat: Long, val name: String, val user: Long? = null, val username: String? = null,
    val bio: String? = null, val phone: String? = null, val photo: ProfilePhoto? = null,
    val kind: ChatKind = ChatKind.Contact, val protectedContent: Boolean = false, val muted: Boolean = false, val canMute: Boolean = true) { override fun toString() = "ContactDetails([redacted])" }
data class SharedLink(val url: String, val title: String? = null, val image: TelegramMedia? = null) { override fun toString() = "SharedLink([redacted])" }
data class SharedEntry(val message: Message, val links: List<SharedLink> = emptyList()) {
    override fun toString() = "SharedEntry([redacted])"
    fun media(protectedContent: Boolean) = message.media?.let { MediaItem(message.chatId, message.id, it, message.canSave && !protectedContent) }
}
data class SharedPage(val entries: List<SharedEntry>, val next: Long)

/** Account-qualified requests on the existing manager; no additional Telegram client or database. */
class ContactLibraryRepository @Inject constructor(private val manager: TdlibClientManager, private val avatars: TdlibAvatarClient) {
    val updates = manager.contactLibraryUpdates
    private suspend fun call(account: Long, value: JSONObject) = avatars.call(value, account)
    suspend fun details(chat: Long, account: Long): ContactDetails {
        val json = call(account, request("getChat", "chat_id" to chat))
        val type = json.optJSONObject("type")
        val user = type?.optLong("user_id")?.takeIf { it > 0 }
        val userJson = user?.let { call(account, request("getUser", "user_id" to it)) }
        val identity = userJson?.let(avatars::user)
        val full = user?.let { try { call(account, request("getUserFullInfo", "user_id" to it)) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null } }
        val notification = json.optJSONObject("notification_settings")
        val kind = if (user != null) ChatKind.Contact else if (type?.optBoolean("is_channel") == true) ChatKind.Channel else ChatKind.Group
        val effectiveMute = if (notification?.optBoolean("use_default_mute_for") == true) {
            val scope = when (kind) { ChatKind.Contact -> "notificationSettingsScopePrivateChats"; ChatKind.Channel -> "notificationSettingsScopeChannelChats"; else -> "notificationSettingsScopeGroupChats" }
            call(account, request("getScopeNotificationSettings", "scope" to request(scope))).optInt("mute_for") > 0
        } else notification?.optInt("mute_for", 0)?.let { it > 0 } == true
        return ContactDetails(chat, identity?.name ?: json.safeText("title"), user, identity?.username,
            full?.optJSONObject("bio")?.safeText("text")?.takeIf(String::isNotBlank),
            userJson?.safeText("phone_number")?.takeIf(String::isNotBlank),
            identity?.small ?: json.optJSONObject("photo")?.optJSONObject("small")?.let(manager::messagingPhoto),
            kind, json.optBoolean("has_protected_content"), effectiveMute, user != account)
    }
    fun updated(details: ContactDetails, update: JSONObject): ContactDetails {
        val user = update.optJSONObject("user")
        if (user != null && user.optLong("id") == details.user) {
            val identity = avatars.user(user)
            return details.copy(name = identity.name, username = identity.username, photo = identity.small,
                phone = user.safeText("phone_number").takeIf(String::isNotBlank))
        }
        if (update.optLong("user_id") == details.user) update.optJSONObject("user_full_info")?.let {
            return details.copy(bio = it.optJSONObject("bio")?.safeText("text")?.takeIf(String::isNotBlank))
        }
        return details
    }
    suspend fun mute(chat: Long, account: Long, muted: Boolean): ContactDetails {
        val chatJson = call(account, request("getChat", "chat_id" to chat))
        val settings = chatJson.getJSONObject("notification_settings")
        // Keep every unrelated override intact.
        settings.put("use_default_mute_for", false).put("mute_for", if (muted) 31536000 else 0)
        call(account, request("setChatNotificationSettings", "chat_id" to chat, "notification_settings" to settings))
        return details(chat, account)
    }
    suspend fun page(chat: Long, account: Long, category: SharedCategory, from: Long, limit: Int): SharedPage = withContext(Dispatchers.IO) {
        require(category != SharedCategory.All)
        val result = call(account, request("searchChatMessages", "chat_id" to chat, "topic_id" to null, "query" to "", "sender_id" to null,
            "from_message_id" to from, "offset" to 0, "limit" to limit.coerceIn(1, 100), "filter" to request("searchMessagesFilter${category.filter}")))
        SharedPage(result.optJSONArray("messages")?.objects()?.mapNotNull(::entry).orEmpty(), result.optLong("next_from_message_id"))
    }
    suspend fun message(chat: Long, account: Long, id: Long): SharedEntry? = withContext(Dispatchers.IO) {
        entry(call(account, request("getMessage", "chat_id" to chat, "message_id" to id)))
    }
    private fun entry(json: JSONObject): SharedEntry? {
        val message = mapTelegramMessage(json)
        if (message.restricted || message.isService) return null
        val content = json.optJSONObject("content") ?: return null
        val text = content.optJSONObject("text") ?: content.optJSONObject("caption")
        val raw = text?.safeText("text").orEmpty()
        val preview = content.optJSONObject("link_preview")
        val links = text?.optJSONArray("entities")?.objects()?.mapNotNull { entity ->
            val type = entity.optJSONObject("type") ?: return@mapNotNull null
            val url = when (type.optString("@type")) {
                "textEntityTypeTextUrl" -> type.safeText("url")
                "textEntityTypeUrl" -> { val start = entity.optInt("offset"); val end = start + entity.optInt("length")
                    if (start < 0 || end > raw.length || end < start) return@mapNotNull null
                    raw.substring(start, end) }
                else -> return@mapNotNull null
            }
            val scheme = android.net.Uri.parse(url).scheme
            if (scheme != null && !scheme.equals("http", true) && !scheme.equals("https", true)) return@mapNotNull null
            val normalized = if (scheme != null) url else "https://$url"
            val matchingPreview = preview?.takeIf { it.safeText("url") == normalized || it.safeText("url") == url }
            val photo = matchingPreview?.optJSONObject("type")?.optJSONObject("photo") ?: matchingPreview?.optJSONObject("photo")
            val image = photo?.let { mapTelegramMedia(request("messagePhoto", "photo" to it)) }
            if (android.net.Uri.parse(normalized).host.isNullOrBlank()) null else SharedLink(normalized,
                matchingPreview?.safeText("title")?.takeIf(String::isNotBlank), image)
        }.orEmpty().distinctBy { it.url }
        return SharedEntry(message, links)
    }
}
