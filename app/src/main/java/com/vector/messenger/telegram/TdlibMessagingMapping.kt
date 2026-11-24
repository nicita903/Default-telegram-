package com.vector.messenger.telegram

import com.vector.core.*
import com.vector.core.messaging.*
import com.vector.core.profile.ProfilePhoto
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val messageTime = DateTimeFormatter.ofPattern("HH:mm")
internal fun telegramTime(date: Long): String = if (date <= 0) "" else
    messageTime.format(Instant.ofEpochSecond(date).atZone(ZoneId.systemDefault()))

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }
internal fun JSONObject.safeText(key: String) = if (isNull(key)) "" else optString(key)
// Android JSONObject.optLong converts strings through Double and can round TDLib's int64 orders.
internal fun JSONObject.exactLong(key: String): Long = when (val value = opt(key)) {
    is String -> value.toLongOrNull() ?: 0L
    is Number -> value.toLong()
    else -> 0L
}
internal fun restricted(json: JSONObject): Boolean {
    val restrictions = json.optJSONObject("restriction_info") ?: return false
    if (restrictions.safeText("restriction_reason").isNotBlank() || restrictions.optBoolean("has_sensitive_content")) return true
    val reasons = restrictions.optJSONArray("restriction_reasons") ?: return false
    return reasons.objects().any { it.optString("platform") in setOf("all", "android") }
}
internal fun contentText(content: JSONObject): String {
    if (restricted(content)) return "Content unavailable"
    val type = content.optString("@type")
    if (type == "messageText") {
        val text = content.optJSONObject("text") ?: return ""
        if (text.optJSONArray("entities")?.objects()?.any { it.optJSONObject("type")?.optString("@type") == "textEntityTypeSpoiler" } == true) return "Spoiler message"
        return text.safeText("text")
    }
    val formattedCaption = content.optJSONObject("caption")
    val caption = if (formattedCaption?.optJSONArray("entities")?.objects()?.any { it.optJSONObject("type")?.optString("@type") == "textEntityTypeSpoiler" } == true)
        "Hidden caption" else formattedCaption?.safeText("text").orEmpty()
    val label = when (type) {
        "messagePhoto" -> "Photo"
        "messageVideo" -> "Video"
        "messageAudio" -> "Audio"
        "messageVoiceNote" -> "Voice message"
        "messageVideoNote" -> "Video message"
        "messageDocument" -> "File"
        "messageAnimation" -> "Animation"
        "messageSticker" -> "Sticker"
        "messagePoll" -> "Poll"
        "messageCall" -> "Call"
        "messageContact" -> "Contact"
        "messageLocation", "messageVenue" -> "Location"
        else -> "Unsupported message"
    }
    if (type == "messagePhoto" || type == "messageDocument" || type == "messageVideoNote") return caption
    return if (caption.isBlank()) label else "$label · $caption"
}
internal fun mapMessageReactions(info: JSONObject?): List<MessageReaction> {
    val values = when (val value = info?.opt("reactions")) {
        is JSONArray -> value
        is JSONObject -> value.optJSONArray("reactions")
        else -> null
    }
    return values?.objects()?.mapNotNull { value ->
        val type = value.optJSONObject("type") ?: return@mapNotNull null
        if (type.optString("@type") != "reactionTypeEmoji") return@mapNotNull null
        val emoji = type.safeText("emoji")
        val count = value.optInt("total_count")
        if (emoji.isEmpty() || count <= 0) null else MessageReaction(emoji, count, value.optBoolean("is_chosen"))
    }.orEmpty()
}
internal fun mapTelegramMessage(json: JSONObject): Message {
    val sending = json.optJSONObject("sending_state")
    val outgoing = json.optBoolean("is_outgoing")
    val blocked = restricted(json) || json.optJSONObject("self_destruct_type") != null || json.optJSONObject("ephemeral_content") != null
    val content = json.optJSONObject("content") ?: JSONObject()
    val failed = sending?.optString("@type") == "messageSendingStateFailed"
    val retryAllowed = failed && sending!!.optBoolean("can_retry") && !sending.optBoolean("need_another_sender") &&
        !sending.optBoolean("need_another_reply_quote") && !sending.optBoolean("need_drop_reply") && sending.optLong("required_paid_message_star_count") == 0L
    val retryAfter = sending?.optDouble("retry_after", 0.0)?.coerceAtLeast(0.0) ?: 0.0
    return Message(
        id = json.getLong("id"), chatId = json.getLong("chat_id"),
        text = if (blocked) "Content unavailable in this client" else com.vector.messenger.calling.mapTelegramCall(json)?.let { call ->
            call.description + if (call.seconds > 0) " · ${call.seconds / 60}:${(call.seconds % 60).toString().padStart(2, '0')}" else ""
        } ?: contentText(content),
        time = telegramTime(json.optLong("date")),
        delivery = when { failed -> Delivery.Failed; sending != null -> Delivery.Sending; outgoing -> Delivery.Sent; else -> Delivery.Received },
        replyTo = json.optJSONObject("reply_to")?.takeIf { it.optString("@type") == "messageReplyToMessage" }?.optLong("message_id")?.takeIf { it != 0L },
        date = json.optLong("date"), outgoing = outgoing, canRetry = retryAllowed,
        retryAt = if (retryAfter > 0) System.currentTimeMillis() + (retryAfter * 1000).toLong() else 0,
        canSave = json.optBoolean("can_be_saved") && !blocked, restricted = blocked,
        senderId = json.optJSONObject("sender_id")?.let { if (it.optString("@type") == "messageSenderUser") it.optLong("user_id") else it.optLong("chat_id") } ?: 0,
        senderName = json.safeText("author_signature").takeIf { it.isNotBlank() },
        media = if (blocked) null else mapTelegramMedia(content),
        mediaAlbumId = json.exactLong("media_album_id"),
        reactions = if (blocked) emptyList() else mapMessageReactions(json.optJSONObject("interaction_info")),
        senderIsChat = json.optJSONObject("sender_id")?.optString("@type") == "messageSenderChat",
        isService = content.optString("@type") !in setOf("messageText", "messagePhoto", "messageVideo", "messageAudio", "messageVoiceNote",
            "messageVideoNote", "messageDocument", "messageAnimation", "messageSticker", "messagePoll", "messageContact", "messageLocation", "messageVenue", "messageDice"),
    )
}
private fun restrictedMessage(message: JSONObject) = restricted(message) || message.optJSONObject("self_destruct_type") != null || message.optJSONObject("ephemeral_content") != null
private fun messagePreview(message: JSONObject): String {
    if (restrictedMessage(message)) return "Content unavailable"
    val content = message.optJSONObject("content") ?: JSONObject()
    return contentText(content).ifEmpty {
        if (content.optString("@type") == "messageVideoNote") "Video message" else if (content.optString("@type") == "messageDocument") content.optJSONObject("document")?.safeText("file_name")?.ifBlank { "File" } ?: "File" else "Photo"
    }
}
internal fun mapPosition(json: JSONObject): ChatPosition? {
    val list = json.optJSONObject("list")?.optString("@type")
    if (list !in setOf("chatListMain", "chatListArchive")) return null
    return ChatPosition(list == "chatListArchive", json.exactLong("order"), json.optBoolean("is_pinned"))
}
internal fun positions(json: JSONObject) = json.optJSONArray("positions")?.objects()?.mapNotNull(::mapPosition).orEmpty()
internal fun mapTelegramChat(json: JSONObject, photo: (JSONObject) -> ProfilePhoto?): TelegramChat {
    val type = json.optJSONObject("type") ?: JSONObject()
    val blocked = restricted(json) || type.optString("@type") == "chatTypeSecret"
    val kind = when (type.optString("@type")) {
        "chatTypeBasicGroup" -> ChatKind.Group
        "chatTypeSupergroup" -> if (type.optBoolean("is_channel")) ChatKind.Channel else ChatKind.Group
        else -> ChatKind.Contact
    }
    val last = json.optJSONObject("last_message")
    return TelegramChat(Chat(json.getLong("id"), json.safeText("title"),
        if (blocked) "Content unavailable" else last?.let(::messagePreview).orEmpty(),
        telegramTime(last?.optLong("date") ?: 0), unread = json.optInt("unread_count"),
        isContact = kind == ChatKind.Contact && !blocked, kind = kind,
        photo = json.optJSONObject("photo")?.optJSONObject("small")?.let(photo),
        canSend = !blocked && (json.optJSONObject("permissions")?.optBoolean("can_send_basic_messages") ?: false),
        protectedContent = blocked || json.optBoolean("has_protected_content"),
        canSendPhotos = !blocked && json.optJSONObject("permissions")?.optBoolean("can_send_photos") == true,
        canSendDocuments = !blocked && json.optJSONObject("permissions")?.optBoolean("can_send_documents") == true,
        userId = type.optLong("user_id").takeIf { it > 0 }), positions(json),
        lastMessageId = last?.optLong("id") ?: 0, lastReadOutbox = json.optLong("last_read_outbox_message_id"),
        peerId = if (type.optString("@type") == "chatTypeSupergroup") type.optLong("supergroup_id") else type.optLong("user_id"),
        supergroup = type.optString("@type") == "chatTypeSupergroup", lastRestricted = last?.let(::restrictedMessage) == true)
}
internal fun mapChatUpdate(json: JSONObject, photo: (JSONObject) -> ProfilePhoto?): ChatUpdate? {
    val id = json.optLong("chat_id")
    return when (json.optString("@type")) {
        "updateNewChat" -> ChatUpdate.Snapshot(mapTelegramChat(json.getJSONObject("chat"), photo))
        "updateChatPosition" -> mapPosition(json.getJSONObject("position"))?.let { ChatUpdate.Position(id, it) }
        "updateChatDraftMessage" -> ChatUpdate.Positions(id, positions(json))
        "updateChatLastMessage" -> {
            val last = json.optJSONObject("last_message")
            ChatUpdate.Preview(id, last?.let(::messagePreview).orEmpty(),
                telegramTime(last?.optLong("date") ?: 0), last?.optLong("id") ?: 0, positions(json), last?.let(::restrictedMessage) == true)
        }
        "updateChatTitle" -> ChatUpdate.Title(id, json.safeText("title"))
        "updateChatPhoto" -> ChatUpdate.Photo(id, json.optJSONObject("photo")?.optJSONObject("small")?.let(photo))
        "updateChatReadInbox" -> ChatUpdate.Unread(id, json.optInt("unread_count"))
        "updateChatReadOutbox" -> ChatUpdate.ReadOutbox(id, json.optLong("last_read_outbox_message_id"))
        "updateChatPermissions" -> ChatUpdate.Permissions(id, json.optJSONObject("permissions")?.optBoolean("can_send_basic_messages") == true,
            json.optJSONObject("permissions")?.optBoolean("can_send_photos") == true,
            json.optJSONObject("permissions")?.optBoolean("can_send_documents") == true)
        "updateChatHasProtectedContent" -> ChatUpdate.Protection(id, json.optBoolean("has_protected_content"))
        "updateNewMessage" -> ChatUpdate.Upsert(mapTelegramMessage(json.getJSONObject("message")), isNew = true)
        "updateMessageSendSucceeded", "updateMessageSendFailed" -> ChatUpdate.Upsert(mapTelegramMessage(json.getJSONObject("message")), json.optLong("old_message_id"))
        "updateMessageContent" -> json.getJSONObject("new_content").let { ChatUpdate.Content(id, json.optLong("message_id"), contentText(it), mapTelegramMedia(it)) }
        "updateMessageInteractionInfo" -> ChatUpdate.Reactions(id, json.optLong("message_id"), mapMessageReactions(json.optJSONObject("interaction_info")))
        "updateDeleteMessages" -> if (json.optBoolean("is_permanent")) ChatUpdate.Deleted(id,
            json.optJSONArray("message_ids")?.let { ids -> (0 until ids.length()).map { ids.optLong(it) } }.orEmpty()) else null
        "updateFile" -> photo(json.getJSONObject("file"))?.let { ChatUpdate.File(it) }
        "updateUser" -> json.getJSONObject("user").let { ChatUpdate.Access(it.optLong("id"), false, restricted(it),
            displayName = listOf(it.safeText("first_name"), it.safeText("last_name")).filter(String::isNotBlank).joinToString(" ").takeIf(String::isNotBlank)) }
        "updateSupergroup" -> json.getJSONObject("supergroup").let { group ->
            val status = group.optJSONObject("status")
            val canSend = when (status?.optString("@type")) {
                "chatMemberStatusCreator" -> true
                "chatMemberStatusAdministrator" -> !group.optBoolean("is_channel") || status.optJSONObject("rights")?.optBoolean("can_post_messages") == true
                "chatMemberStatusLeft", "chatMemberStatusBanned" -> false
                "chatMemberStatusRestricted" -> status.optBoolean("is_member") && status.optJSONObject("permissions")?.optBoolean("can_send_basic_messages") == true
                else -> null
            }
            val canSendPhotos = if (status?.optString("@type") == "chatMemberStatusRestricted")
                status.optBoolean("is_member") && status.optJSONObject("permissions")?.optBoolean("can_send_photos") == true else canSend
            ChatUpdate.Access(group.optLong("id"), true, restricted(group), canSend, canSendPhotosOverride = canSendPhotos)
        }
        else -> null
    }
}
