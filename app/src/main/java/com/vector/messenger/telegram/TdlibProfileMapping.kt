package com.vector.messenger.telegram

import com.vector.core.profile.*
import org.json.JSONObject
import java.io.File

/** Only active_usernames are public handles; editable_username can be disabled. */
internal fun mapProfile(json: JSONObject, filesRoot: File): TelegramProfile {
    require(json.optString("@type") == "user" && json.optLong("id") > 0) { "Invalid Telegram user object" }
    val usernames = json.optJSONObject("usernames")?.optJSONArray("active_usernames")
    val username = (0 until (usernames?.length() ?: 0)).asSequence()
        .filter { !usernames!!.isNull(it) }.map { usernames!!.optString(it) }.firstOrNull { it.isNotBlank() }
    return TelegramProfile(
        id = json.getLong("id"), firstName = json.profileText("first_name"), lastName = json.profileText("last_name"),
        username = username, phoneNumber = json.profileText("phone_number").takeIf { it.isNotBlank() },
        photo = json.optJSONObject("profile_photo")?.optJSONObject("small")?.let { mapProfileFile(it, filesRoot) },
    )
}

private fun JSONObject.profileText(key: String) = if (isNull(key)) "" else optString(key)

internal fun mapProfileFile(json: JSONObject, filesRoot: File): ProfilePhoto? {
    val id = json.optInt("id")
    if (id <= 0) return null
    val local = json.optJSONObject("local")
    val path = local?.optString("path")?.takeIf { it.isNotBlank() && local.optBoolean("is_downloading_completed") }
    val safePath = try {
        path?.let { File(it).canonicalFile }?.takeIf {
            it.toPath().startsWith(filesRoot.canonicalFile.toPath()) && it.isFile
        }?.path
    } catch (_: Exception) { null }
    return ProfilePhoto(id, safePath)
}

internal fun mapProfileConnection(json: JSONObject): ProfileConnection = when (json.optString("@type")) {
    "connectionStateReady" -> ProfileConnection.Ready
    "connectionStateWaitingForNetwork" -> ProfileConnection.WaitingForNetwork
    "connectionStateUpdating" -> ProfileConnection.Updating
    else -> ProfileConnection.Connecting
}
