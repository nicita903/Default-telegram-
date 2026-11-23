package com.vector.messenger.telegram

import android.content.Context
import com.vector.core.profile.ProfilePhoto
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal data class TelegramPost(val id: Int, val chat: Long, val date: Long, val caption: String,
    val file: ProfilePhoto?, val thumbnail: ProfilePhoto?, val video: Boolean, val width: Int, val height: Int,
    val canDelete: Boolean, val canRemove: Boolean, val posted: Boolean, val pending: Boolean, val protected: Boolean,
    val viewCount: Long? = null, val reactionCount: Long? = null)
internal class StoryFailure(val description: String, val uncertain: Boolean = false) : Exception("Telegram Story operation failed")

internal class TdlibStoryClient @Inject constructor(private val manager: TdlibClientManager,
    @ApplicationContext context: Context, private val avatars: TdlibAvatarClient) {
    val updates = manager.storyUpdates
    val connection = manager.connection
    private val secureStories = File(context.noBackupFilesDir, "telegram/database/stories")
    suspend fun call(json: JSONObject): JSONObject {
        val result = manager.messagingRequest(json)
        if (result.optString("@type") == "error") throw StoryFailure(error(result), result.optInt("code") >= 500 || result.optInt("code") == 0)
        return result
    }
    fun error(json: JSONObject): String {
        val code = json.optInt("code")
        val message = if (code == 406) "" else json.safeText("message")
        return when {
            message.contains("PREMIUM") -> "Telegram requires Premium for this Story action on your account."
            message.contains("STORIES_TOO_MUCH") || message.contains("STORY_COUNT") -> "Telegram's Story publishing limit has been reached."
            message.contains("CAPTION_TOO_LONG") -> "The caption exceeds your account's Telegram Story limit."
            message.contains("VIDEO") || message.contains("PHOTO") || message.contains("IMAGE") -> "Telegram rejected this media. Check the Story photo/video format and duration."
            message.contains("METHOD") || message.contains("Unknown class", true) -> "This installed TDLib version does not support this Story operation."
            code == 429 || message.startsWith("FLOOD_WAIT") -> "Telegram has temporarily limited this action. Try again later."
            else -> "Telegram could not complete the Story action. Check connectivity and try again."
        }
    }
    fun restriction(result: JSONObject): String? = when (result.optString("@type")) {
        "canPostStoryResultOk" -> null
        "canPostStoryResultPremiumNeeded" -> "Telegram requires Premium to publish Stories with this account."
        "canPostStoryResultBoostNeeded" -> "Telegram requires more boosts before this chat can publish Stories."
        "canPostStoryResultActiveStoryLimitExceeded" -> "Telegram's active Story limit is reached. Wait for a Story to expire or manage existing Stories."
        "canPostStoryResultWeeklyLimitExceeded" -> "Telegram's weekly Story limit is reached. Retry after ${result.optInt("retry_after")} seconds."
        "canPostStoryResultMonthlyLimitExceeded" -> "Telegram's monthly Story limit is reached. Retry after ${result.optInt("retry_after")} seconds."
        "canPostStoryResultLiveStoryIsActive" -> "Telegram requires the active live Story to end before another Story can be posted."
        else -> "Story publishing is unavailable for this account."
    }
    fun file(json: JSONObject): ProfilePhoto? {
        val normal = manager.messagingPhoto(json)
        return if (normal?.localPath != null) normal else mapProfileFile(json, secureStories) ?: normal
    }
    fun map(json: JSONObject): TelegramPost {
        val content = json.optJSONObject("content")
        val isVideo = content?.optString("@type") == "storyContentVideo"
        var main: JSONObject? = null; var thumbnail: JSONObject? = null; var width = 0; var height = 0
        if (isVideo) {
            val video = content?.optJSONObject("alternative_video") ?: content?.optJSONObject("video")
            main = video?.optJSONObject("video"); thumbnail = video?.optJSONObject("thumbnail")?.optJSONObject("file")
            width = video?.optInt("width") ?: 0; height = video?.optInt("height") ?: 0
        } else if (content?.optString("@type") == "storyContentPhoto") {
            val sizes = content.optJSONObject("photo")?.optJSONArray("sizes")?.objects().orEmpty()
            val big = sizes.maxByOrNull { it.optLong("width") * it.optLong("height") }
            main = big?.optJSONObject("photo")
            thumbnail = sizes.minByOrNull { kotlin.math.abs(maxOf(it.optInt("width"), it.optInt("height")) - 320) }?.optJSONObject("photo")
            width = big?.optInt("width") ?: 0; height = big?.optInt("height") ?: 0
        }
        val interactions = json.optJSONObject("interaction_info")
        fun count(field: String): Long? = interactions?.takeIf { it.has(field) && !it.isNull(field) }?.optLong(field)?.takeIf { it >= 0 }
        return TelegramPost(json.optInt("id"), json.optLong("poster_chat_id"), json.optLong("date"),
            json.optJSONObject("caption")?.safeText("text").orEmpty(), main?.let(::file), thumbnail?.let(::file), isVideo, width, height,
            json.optBoolean("can_be_deleted"), json.optBoolean("can_toggle_is_posted_to_chat_page"), json.optBoolean("is_posted_to_chat_page"),
            json.optBoolean("is_being_posted"), !json.optBoolean("can_be_forwarded"), count("view_count"), count("reaction_count"))
    }
    suspend fun contacts(query: String): List<AvatarIdentity> {
        val users = call(request("searchContacts", "query" to query, "limit" to 100)).optJSONArray("user_ids")
        val ids = (0 until (users?.length() ?: 0)).mapNotNull { users?.optLong(it)?.takeIf { id -> id > 0 } }
        return ids.chunked(4).flatMap { batch -> coroutineScope { batch.map { id -> async { avatars.user(call(request("getUser", "user_id" to id))) } }.awaitAll() } }
    }
    suspend fun selfChat(account: Long): Long {
        val me = call(request("getMe"))
        if (me.optLong("id") != account) throw StoryFailure("The active Telegram account changed.")
        return call(request("createPrivateChat", "user_id" to account, "force" to false)).getLong("id")
    }
    suspend fun list(chat: Long, from: Int) = call(request("getChatPostedToChatPageStories", "chat_id" to chat, "from_story_id" to from, "limit" to 30))
    suspend fun get(chat: Long, id: Int) = call(request("getStory", "story_poster_chat_id" to chat, "story_id" to id, "only_local" to false))
    suspend fun download(id: Int) = call(request("downloadFile", "file_id" to id, "priority" to 1, "offset" to 0, "limit" to 0, "synchronous" to false))
    suspend fun publish(chat: Long, content: JSONObject, caption: String, visibility: String, selected: List<Long>): JSONObject {
        val privacy = when (visibility) {
            "Everyone" -> request("storyPrivacySettingsEveryone", "except_user_ids" to JSONArray())
            "Contacts" -> request("storyPrivacySettingsContacts", "except_user_ids" to JSONArray())
            "Selected" -> { if (selected.isEmpty()) throw StoryFailure("Select at least one Telegram contact."); request("storyPrivacySettingsSelectedUsers", "user_ids" to JSONArray(selected)) }
            else -> throw StoryFailure("Choose Story visibility before publishing.")
        }
        return call(request("postStory", "chat_id" to chat, "content" to content, "areas" to null,
            "caption" to request("formattedText", "text" to caption, "entities" to JSONArray()), "music" to null,
            "privacy_settings" to privacy, "album_ids" to JSONArray(), "active_period" to 86400,
            "from_story_full_id" to null, "is_posted_to_chat_page" to true, "protect_content" to false))
    }
    suspend fun remove(chat: Long, id: Int, delete: Boolean) {
        val fresh = map(get(chat, id))
        if (delete && !fresh.canDelete || !delete && !fresh.canRemove) throw StoryFailure("Telegram no longer permits this Story action.")
        if (delete) call(request("deleteStory", "story_poster_chat_id" to chat, "story_id" to id))
        else call(request("toggleStoryIsPostedToChatPage", "story_poster_chat_id" to chat, "story_id" to id, "is_posted_to_chat_page" to false))
    }
}
