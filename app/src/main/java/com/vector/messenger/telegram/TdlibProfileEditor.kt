package com.vector.messenger.telegram

import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

internal data class EditableProfile(val account: Long, val first: String, val last: String, val username: String, val bio: String, val bioLimit: Int)
internal class ProfileEditFailure(val explanation: String) : Exception("Profile update rejected")

/** Uses the existing authenticated manager, including its session-epoch checks. */
internal class TdlibProfileEditor @Inject constructor(private val manager: TdlibClientManager) {
    val updates = manager.avatarUpdates
    private suspend fun call(json: JSONObject): JSONObject {
        val response = manager.messagingRequest(json)
        if (response.optString("@type") == "error") {
            val code = response.optInt("code")
            val error = if (code == 406) "" else response.optString("message")
            throw ProfileEditFailure(when {
                error == "USERNAME_OCCUPIED" -> "That username is already in use."
                error == "USERNAME_INVALID" -> "Telegram rejected this username. Use letters, digits and underscores."
                error == "USERNAME_PURCHASE_AVAILABLE" -> "This username is available only through Telegram's purchase process."
                error == "USERNAMES_ACTIVE_TOO_MUCH" -> "Telegram requires you to manage your other active usernames before removing this one."
                error == "ABOUT_TOO_LONG" -> "The biography exceeds this account's Telegram limit."
                error.contains("FIRSTNAME_INVALID") || error.contains("LASTNAME_INVALID") -> "Telegram rejected the name. Check its length and characters."
                code == 429 || error.startsWith("FLOOD_WAIT") -> "Telegram has temporarily limited profile changes. Wait before trying again."
                else -> "Telegram could not apply this change. Check your connection and retry."
            })
        }
        return response
    }
    suspend fun load(account: Long): EditableProfile {
        val user = call(request("getMe"))
        if (user.optLong("id") != account) throw ProfileEditFailure("The active account changed. Reopen Profile.")
        val full = call(request("getUserFullInfo", "user_id" to account))
        val limit = call(request("getOption", "name" to "bio_length_max")).exactLong("value").toInt().takeIf { it in 1..4096 }
            ?: throw ProfileEditFailure("Telegram biography limits are unavailable. Refresh when connected.")
        return EditableProfile(account, user.safeText("first_name"), user.safeText("last_name"),
            user.optJSONObject("usernames")?.safeText("editable_username").orEmpty(), bio(full), limit)
    }
    fun bio(full: JSONObject) = full.optJSONObject("bio")?.safeText("text").orEmpty()
    suspend fun save(original: EditableProfile, draft: EditableProfile, active: () -> Boolean): EditableProfile {
        suspend fun apply(type: String, vararg fields: Pair<String, Any?>) {
            if (!active()) throw CancellationException("Account changed")
            call(request(type, *fields))
        }
        if (original.first != draft.first || original.last != draft.last) apply("setName", "first_name" to draft.first, "last_name" to draft.last)
        if (original.username != draft.username) apply("setUsername", "username" to draft.username)
        if (original.bio != draft.bio) apply("setBio", "bio" to draft.bio)
        if (!active()) throw CancellationException("Account changed")
        val verified = load(draft.account)
        if (verified.first != draft.first || verified.last != draft.last || verified.username != draft.username || verified.bio != draft.bio)
            throw ProfileEditFailure("Telegram returned different profile values. Reload your profile before retrying.")
        return verified
    }
}
