package com.vector.messenger.telegram

import com.vector.core.profile.ProfilePhoto
import javax.inject.Inject
import org.json.JSONObject
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.awaitAll

data class AvatarOwner(val id: Long, val user: Boolean = true)
data class AvatarIdentity(val owner: AvatarOwner, val name: String, val small: ProfilePhoto?, val big: ProfilePhoto? = null,
    val photoId: Long = 0, val userId: Long? = if (owner.user) owner.id else null, val canSave: Boolean = true,
    val username: String? = null)
data class AvatarPicture(val id: Long, val small: ProfilePhoto?, val big: ProfilePhoto, val date: Long = 0)
data class AvatarPage(val photos: List<AvatarPicture>, val offset: Int, val fromMessage: Long, val more: Boolean)
data class AvatarMemberPage(val members: List<AvatarIdentity>, val offset: Int, val more: Boolean, val allowed: Boolean = true)

/** Adapter over the one authenticated JSON client; never consumes the chat repository's channel. */
class TdlibAvatarClient @Inject constructor(private val manager: TdlibClientManager) {
    val updates = manager.avatarUpdates
    suspend fun call(value: JSONObject): JSONObject {
        val result = manager.messagingRequest(value)
        if (result.optString("@type") == "error") throw com.vector.core.messaging.MessagingException(
            if (result.optInt("code") in 500..599 || result.optInt("code") == 0) com.vector.core.messaging.MessagingFailure.Network
            else com.vector.core.messaging.MessagingFailure.Unavailable)
        return result
    }
    fun file(json: JSONObject): ProfilePhoto? = manager.messagingPhoto(json)
    fun user(json: JSONObject): AvatarIdentity {
        val owner = AvatarOwner(json.optLong("id"))
        val photo = json.optJSONObject("profile_photo")
        val blocked = restricted(json)
        val active = json.optJSONObject("usernames")?.optJSONArray("active_usernames")
        return AvatarIdentity(owner, listOf(json.safeText("first_name"), json.safeText("last_name")).filter(String::isNotBlank).joinToString(" "),
            if (blocked) null else photo?.optJSONObject("small")?.let(::file), if (blocked) null else photo?.optJSONObject("big")?.let(::file),
            photo?.exactLong("id") ?: 0, canSave = !blocked, username = active?.optString(0)?.takeIf(String::isNotBlank))
    }
    suspend fun identity(owner: AvatarOwner): AvatarIdentity {
        if (owner.user) return user(call(request("getUser", "user_id" to owner.id)))
        val chat = call(request("getChat", "chat_id" to owner.id))
        val peer = chat.optJSONObject("type")?.optLong("user_id")?.takeIf { it > 0 }
        if (peer != null) {
            val user = identity(AvatarOwner(peer))
            return user.copy(owner = owner, name = chat.safeText("title"), canSave = user.canSave && !chat.optBoolean("has_protected_content"))
        }
        val photo = chat.optJSONObject("photo")
        return AvatarIdentity(owner, chat.safeText("title"), photo?.optJSONObject("small")?.let(::file), photo?.optJSONObject("big")?.let(::file),
            canSave = !chat.optBoolean("has_protected_content") && !restricted(chat))
    }
    private fun picture(json: JSONObject): AvatarPicture? {
        val sizes = json.optJSONArray("sizes")?.objects().orEmpty()
        val big = sizes.maxByOrNull { it.optLong("width") * it.optLong("height") }?.optJSONObject("photo")?.let(::file) ?: return null
        val small = sizes.minByOrNull { it.optLong("width") * it.optLong("height") }?.optJSONObject("photo")?.let(::file)
        return AvatarPicture(json.exactLong("id"), small, big, json.optLong("added_date"))
    }
    suspend fun history(identity: AvatarIdentity, offset: Int, fromMessage: Long): AvatarPage {
        val userId = identity.userId
        if (userId != null) {
            val result = call(request("getUserProfilePhotos", "user_id" to userId, "offset" to offset, "limit" to 20))
            val raw = result.optJSONArray("photos")?.objects().orEmpty()
            return AvatarPage(raw.mapNotNull(::picture), offset + raw.size, 0, offset + raw.size < result.optInt("total_count") && raw.isNotEmpty())
        }
        val result = call(request("searchChatMessages", "chat_id" to identity.owner.id, "topic_id" to null, "query" to "", "sender_id" to null,
            "from_message_id" to fromMessage, "offset" to 0, "limit" to 20, "filter" to request("searchMessagesFilterChatPhoto")))
        val messages = result.optJSONArray("messages")?.objects().orEmpty()
        val next = result.optLong("next_from_message_id")
        return AvatarPage(messages.mapNotNull { it.optJSONObject("content")?.optJSONObject("photo")?.let(::picture) }, 0, next,
            next != 0L && next != fromMessage && messages.isNotEmpty())
    }
    suspend fun download(id: Int): JSONObject = call(request("downloadFile", "file_id" to id, "priority" to 1, "offset" to 0, "limit" to 0, "synchronous" to false))
    suspend fun members(owner: AvatarOwner, offset: Int): AvatarMemberPage {
        if (owner.user) return AvatarMemberPage(emptyList(), 0, false, false)
        val type = call(request("getChat", "chat_id" to owner.id)).optJSONObject("type")
        val result = when (type?.optString("@type")) {
            "chatTypeBasicGroup" -> call(request("getBasicGroupFullInfo", "basic_group_id" to type.optLong("basic_group_id")))
            "chatTypeSupergroup" -> {
                val id = type.optLong("supergroup_id")
                if (!call(request("getSupergroupFullInfo", "supergroup_id" to id)).optBoolean("can_get_members"))
                    return AvatarMemberPage(emptyList(), 0, false, false)
                call(request("getSupergroupMembers", "supergroup_id" to id, "filter" to null, "offset" to offset, "limit" to 50))
            }
            else -> return AvatarMemberPage(emptyList(), 0, false, false)
        }
        val all = result.optJSONArray("members")?.objects().orEmpty()
        val raw = if (type?.optString("@type") == "chatTypeBasicGroup") all.drop(offset).take(50) else all
        val users = raw.mapNotNull { it.optJSONObject("member_id") }.filter { it.optString("@type") == "messageSenderUser" }
        val mapped = users.chunked(4).flatMap { batch -> coroutineScope {
            batch.map { sender -> async { identity(AvatarOwner(sender.optLong("user_id"))) } }.awaitAll()
        } }
        val next = offset + raw.size
        return AvatarMemberPage(mapped, next, raw.isNotEmpty() && next < if (type?.optString("@type") == "chatTypeBasicGroup") all.size else result.optInt("total_count"))
    }
    suspend fun currentFile(id: Int): JSONObject = call(request("getFile", "file_id" to id))
    suspend fun setPhoto(path: String) { call(request("setProfilePhoto", "photo" to request("inputChatPhotoStatic", "photo" to request("inputFileLocal", "path" to path)), "is_public" to false)) }
    suspend fun deletePhoto(id: Long) { call(request("deleteProfilePhoto", "profile_photo_id" to id)) }
}
