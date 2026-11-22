package com.vector.messenger.telegram

import com.vector.core.ghost.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

internal fun canonicalJson(value: Any?): String = when (value) {
    is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonicalJson(value.get(it)) }
    is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonicalJson(value.get(it)) }
    null, JSONObject.NULL -> "null"
    is String -> JSONObject.quote(value)
    else -> value.toString()
}
internal fun decodePrivacyRules(array: JSONArray): List<PrivacyRule> = array.objects().map { rule ->
    val users = rule.optJSONArray("user_ids")
    if (rule.optString("@type") == "userPrivacySettingRuleRestrictUsers")
        PrivacyRule.DenyUsers((0 until (users?.length() ?: 0)).map { users!!.getLong(it) }.toSortedSet())
    else if (rule.optString("@type") == "userPrivacySettingRuleAllowUsers")
        PrivacyRule.AllowUsers((0 until (users?.length() ?: 0)).map { users!!.getLong(it) }.toSortedSet())
    else {
        // User-id order is immaterial; normalize known set-valued fields without dropping unknown fields.
        if (users != null) rule.put("user_ids", JSONArray((0 until users.length()).map { users.getLong(it) }.sorted()))
        PrivacyRule.Other(canonicalJson(rule), users?.length() ?: 0)
    }
}
internal fun encodePrivacyRules(rules: List<PrivacyRule>) = JSONArray().apply {
    rules.forEach { rule -> put(when (rule) {
        is PrivacyRule.DenyUsers -> request("userPrivacySettingRuleRestrictUsers", "user_ids" to JSONArray(rule.ids.sorted()))
        is PrivacyRule.AllowUsers -> request("userPrivacySettingRuleAllowUsers", "user_ids" to JSONArray(rule.ids.sorted()))
        is PrivacyRule.Other -> JSONObject(rule.json)
    }) }
}
internal fun ghostContact(user: JSONObject, manager: TdlibClientManager): GhostContact = GhostContact(
    user.getLong("id"), listOf(user.safeText("first_name"), user.safeText("last_name")).filter(String::isNotBlank).joinToString(" ").ifBlank { "Unnamed Telegram contact" },
    user.optJSONObject("usernames")?.optJSONArray("active_usernames")?.optString(0)?.takeIf { it.isNotBlank() },
    user.optJSONObject("profile_photo")?.optJSONObject("small")?.let(manager::messagingPhoto), premium = user.optBoolean("is_premium"))

class TdlibGhostClient(private val manager: TdlibClientManager) : GhostClient {
    private val setting get() = request("userPrivacySettingShowStatus")
    override val events: Flow<GhostEvent> = manager.ghostUpdates.mapNotNull { json ->
        try { when (json.optString("@type")) {
            "updateUserPrivacySettingRules" -> if (json.optJSONObject("setting")?.optString("@type") == "userPrivacySettingShowStatus")
                GhostEvent.Rules(decodePrivacyRules(json.getJSONObject("rules").getJSONArray("rules"))) else null
            "updateUser" -> json.getJSONObject("user").let { GhostEvent.Contact(ghostContact(it, manager), it.optBoolean("is_contact") && it.optJSONObject("type")?.optString("@type") == "userTypeRegular") }
            else -> null
        } } catch (_: Exception) { null }
    }.flowOn(Dispatchers.IO)
    private suspend fun call(payload: JSONObject): JSONObject {
        val result = manager.messagingRequest(payload)
        check(result.optString("@type") != "error") { "Telegram privacy request failed" }
        return result
    }
    override suspend fun accountId() = call(request("getMe")).getLong("id")
    override suspend fun contacts(): List<GhostContact> = withContext(Dispatchers.IO) {
        val users = call(request("getContacts")).getJSONArray("user_ids")
        val semaphore = Semaphore(4)
        coroutineScope { (0 until users.length()).map { index -> async {
            semaphore.withPermit {
                val user = call(request("getUser", "user_id" to users.getLong(index)))
                if (user.optBoolean("is_contact") && user.optJSONObject("type")?.optString("@type") == "userTypeRegular") ghostContact(user, manager) else null
            }
        } }.awaitAll().filterNotNull() }
    }
    override suspend fun rules() = decodePrivacyRules(call(request("getUserPrivacySettingRules", "setting" to setting)).getJSONArray("rules"))
    override suspend fun setRules(rules: List<PrivacyRule>) {
        call(request("setUserPrivacySettingRules", "setting" to setting, "rules" to request("userPrivacySettingRules", "rules" to encodePrivacyRules(rules))))
    }
    override fun installActionPolicy(policy: (Long, String) -> Boolean) = manager.installChatActionPolicy(policy)
    override suspend fun chatAction(chatId: Long, action: String): Boolean {
        if (!manager.hasActionTarget(chatId)) call(request("getChat", "chat_id" to chatId))
        val result = call(request("sendChatAction", "chat_id" to chatId, "topic_id" to null, "business_connection_id" to "", "action" to request(action)))
        return !result.optBoolean("@default_suppressed")
    }
}
