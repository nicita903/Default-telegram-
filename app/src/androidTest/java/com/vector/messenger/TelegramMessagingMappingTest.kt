package com.vector.messenger

import com.vector.core.*
import com.vector.core.messaging.*
import com.vector.messenger.telegram.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelegramMessagingMappingTest {
    private fun message() = JSONObject("""{"@type":"message","id":1234567890123,"chat_id":-1001234567,"date":1700000000,"is_outgoing":true,"can_be_saved":true,"content":{"@type":"messageText","text":{"text":"Synthetic fixture","entities":[]}}}""")
    @Test fun negativeChatIdsAnd64BitOrdersRemainExactAndGroupsChannelsAreDistinct() {
        val json = JSONObject("""{"id":-1001234567,"title":"Synthetic","type":{"@type":"chatTypeSupergroup","supergroup_id":42,"is_channel":true},"permissions":{"can_send_basic_messages":false},"positions":[{"list":{"@type":"chatListMain"},"order":"9223372036854775806","is_pinned":true}],"unread_count":7}""")
        val mapped = mapTelegramChat(json) { null }
        assertEquals(-1001234567L, mapped.chat.id); assertEquals(ChatKind.Channel, mapped.chat.kind)
        assertEquals(9223372036854775806L, mapped.positions.single().order)
        assertTrue(mapped.positions.single().pinned); assertEquals(7, mapped.chat.unread)
        assertFalse(mapped.chat.canSend)
        json.getJSONObject("type").put("is_channel", false)
        assertEquals(ChatKind.Group, mapTelegramChat(json) { null }.chat.kind)
    }
    @Test fun sendingFailedAndServerAcceptedMessagesHaveDistinctStates() {
        val json = message()
        assertEquals(Delivery.Sent, mapTelegramMessage(json).delivery)
        json.put("sending_state", JSONObject().put("@type", "messageSendingStatePending"))
        assertEquals(Delivery.Sending, mapTelegramMessage(json).delivery)
        json.put("sending_state", JSONObject().put("@type", "messageSendingStateFailed").put("can_retry", true).put("retry_after", 20))
        val failed = mapTelegramMessage(json)
        assertEquals(Delivery.Failed, failed.delivery); assertTrue(failed.canRetry)
        assertTrue(failed.retryAt > System.currentTimeMillis())
        json.getJSONObject("sending_state").put("required_paid_message_star_count", 10)
        assertFalse(mapTelegramMessage(json).canRetry)
        json.put("sending_state", JSONObject.NULL).put("is_outgoing", false)
        assertEquals(Delivery.Received, mapTelegramMessage(json).delivery)
    }
    @Test fun updatesCarryReplacementIdsUnreadValuesAndPhotoRemoval() {
        val update = JSONObject().put("@type", "updateMessageSendSucceeded").put("old_message_id", 99).put("message", message())
        val sent = mapChatUpdate(update) { null } as ChatUpdate.Upsert
        assertEquals(99L, sent.oldId); assertEquals(1234567890123L, sent.message.id)
        val unread = mapChatUpdate(JSONObject().put("@type", "updateChatReadInbox").put("chat_id", -100).put("unread_count", 4)) { null } as ChatUpdate.Unread
        assertEquals(4, unread.count)
        val photo = mapChatUpdate(JSONObject().put("@type", "updateChatPhoto").put("chat_id", -100).put("photo", JSONObject.NULL)) { null } as ChatUpdate.Photo
        assertNull(photo.photo)
    }
    @Test fun restrictedSelfDestructingAndSpoilerMessagesNeverExposeTheirPlaintext() {
        val json = message().put("restriction_info", JSONObject().put("restriction_reason", "Synthetic restriction"))
        val blocked = mapTelegramMessage(json)
        assertTrue(blocked.restricted); assertFalse(blocked.canSave); assertFalse(blocked.text.contains("Synthetic fixture"))
        json.remove("restriction_info"); json.put("self_destruct_type", JSONObject().put("@type", "messageSelfDestructTypeImmediately"))
        assertTrue(mapTelegramMessage(json).restricted)
        json.remove("self_destruct_type")
        json.getJSONObject("content").getJSONObject("text").put("entities", org.json.JSONArray().put(JSONObject().put("type", JSONObject().put("@type", "textEntityTypeSpoiler"))))
        assertEquals("Spoiler message", mapTelegramMessage(json).text)
    }
    @Test fun permanentDeletesAreAppliedButCacheEvictionsAreNotConversationDeletes() {
        val json = JSONObject().put("@type", "updateDeleteMessages").put("chat_id", -100).put("message_ids", org.json.JSONArray().put(7)).put("is_permanent", false)
        assertNull(mapChatUpdate(json) { null })
        json.put("is_permanent", true)
        assertEquals(listOf(7L), (mapChatUpdate(json) { null } as ChatUpdate.Deleted).ids)
    }
}
