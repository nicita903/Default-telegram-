package com.vector.messenger.telegram

import com.vector.core.messaging.*
import com.vector.core.profile.ProfilePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

/** Typed adapter over the original manager. No JNI client or receive coroutine is created here. */
class TdlibMessagingClient(private val manager: TdlibClientManager) : TelegramMessagingClient {
    override val connection = manager.connection
    override val updates = manager.messagingUpdates.mapNotNull { update ->
        try { mapChatUpdate(update, manager::messagingPhoto) } catch (_: Exception) { null }
    }.flowOn(Dispatchers.IO)
    private suspend fun call(payload: JSONObject): JSONObject {
        val result = manager.messagingRequest(payload)
        if (result.optString("@type") == "error") throw MessagingException(when (result.optInt("code")) {
            403 -> MessagingFailure.Forbidden
            in 500..599, 0 -> MessagingFailure.Network
            else -> MessagingFailure.Unavailable
        })
        return result
    }
    private fun list(archived: Boolean) = request(if (archived) "chatListArchive" else "chatListMain")
    override suspend fun loadChats(archived: Boolean, limit: Int): Boolean {
        val result = manager.messagingRequest(request("loadChats", "chat_list" to list(archived), "limit" to limit))
        if (result.optString("@type") == "error") {
            if (result.optInt("code") == 404) return false
            throw MessagingException(MessagingFailure.Unavailable)
        }
        return true
    }
    override suspend fun history(chatId: Long, fromId: Long, limit: Int) =
        call(request("getChatHistory", "chat_id" to chatId, "from_message_id" to fromId, "offset" to 0,
            "limit" to limit.coerceIn(1, 100), "only_local" to false)).optJSONArray("messages")?.objects()?.map(::mapTelegramMessage).orEmpty()
    override suspend fun sendText(chatId: Long, text: String, replyTo: Long?) = mapTelegramMessage(call(
        request("sendMessage", "chat_id" to chatId, "topic_id" to null, "reply_to" to replyTo?.let {
            request("inputMessageReplyToMessage", "message_id" to it, "quote" to null, "checklist_task_id" to 0, "poll_option_id" to "") },
            "options" to null, "reply_markup" to null, "input_message_content" to request("inputMessageText",
                "text" to request("formattedText", "text" to text, "entities" to JSONArray()), "link_preview_options" to null, "clear_draft" to false))))
    override suspend fun toggleReaction(chatId: Long, messageId: Long, emoji: String): List<com.vector.core.MessageReaction> {
        fun sameEmoji(value: String) = value.replace("\uFE0F", "") == emoji.replace("\uFE0F", "")
        suspend fun current() = call(request("getMessage", "chat_id" to chatId, "message_id" to messageId))
        val chosen = mapMessageReactions(current().optJSONObject("interaction_info")).firstOrNull { sameEmoji(it.emoji) && it.chosen }
        if (chosen == null) {
            val available = call(request("getMessageAvailableReactions", "chat_id" to chatId, "message_id" to messageId, "row_size" to 8))
            val allowed = listOf("top_reactions", "recent_reactions", "popular_reactions").any { key ->
                available.optJSONArray(key)?.objects()?.any { reaction ->
                    val type = reaction.optJSONObject("type")
                    type?.optString("@type") == "reactionTypeEmoji" && sameEmoji(type.safeText("emoji"))
                } == true
            }
            if (!allowed || available.optJSONObject("unavailability_reason") != null) throw MessagingException(MessagingFailure.Forbidden)
        }
        val type = request("reactionTypeEmoji", "emoji" to (chosen?.emoji ?: emoji))
        call(if (chosen != null) request("removeMessageReaction", "chat_id" to chatId, "message_id" to messageId, "reaction_type" to type)
            else request("addMessageReaction", "chat_id" to chatId, "message_id" to messageId, "reaction_type" to type,
                "is_big" to false, "update_recent_reactions" to true))
        return mapMessageReactions(current().optJSONObject("interaction_info"))
    }
    override suspend fun editText(chatId: Long, messageId: Long, text: String): com.vector.core.Message {
        val properties = call(request("getMessageProperties", "chat_id" to chatId, "message_id" to messageId))
        if (!properties.optBoolean("can_be_edited")) throw MessagingException(MessagingFailure.Forbidden)
        return mapTelegramMessage(call(request("editMessageText", "chat_id" to chatId, "message_id" to messageId,
            "reply_markup" to null, "input_message_content" to request("inputMessageText",
                "text" to request("formattedText", "text" to text, "entities" to JSONArray()),
                "link_preview_options" to null, "clear_draft" to false))))
    }
    override suspend fun sendVoice(chatId: Long, clip: com.vector.core.audio.VoiceClip, replyTo: Long?) = mapTelegramMessage(call(
        voiceMessageRequest(chatId, clip, replyTo)))
    override suspend fun resend(chatId: Long, messageId: Long) = call(request("resendMessages", "chat_id" to chatId,
        "message_ids" to JSONArray().put(messageId), "quote" to null, "paid_message_star_count" to 0)).optJSONArray("messages")?.objects()?.map(::mapTelegramMessage).orEmpty()
    override suspend fun openChat(chatId: Long) { call(request("openChat", "chat_id" to chatId)) }
    override suspend fun closeChat(chatId: Long) { call(request("closeChat", "chat_id" to chatId)) }
    override suspend fun view(chatId: Long, ids: List<Long>) { call(request("viewMessages", "chat_id" to chatId, "message_ids" to JSONArray(ids),
        "source" to request("messageSourceChatHistory"), "force_read" to false)) }
    override suspend fun pin(chatId: Long, archived: Boolean, pinned: Boolean) { call(request("toggleChatIsPinned", "chat_list" to list(archived), "chat_id" to chatId, "is_pinned" to pinned)) }
    override suspend fun archive(chatId: Long, archived: Boolean) { call(request("addChatToList", "chat_id" to chatId, "chat_list" to list(archived))) }
    override suspend fun downloadAvatar(fileId: Int): ProfilePhoto = manager.messagingPhoto(call(request("downloadFile", "file_id" to fileId,
        "priority" to 1, "offset" to 0, "limit" to 0, "synchronous" to false))) ?: ProfilePhoto(fileId)
}
