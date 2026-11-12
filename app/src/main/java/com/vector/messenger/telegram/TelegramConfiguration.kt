package com.vector.messenger.telegram

import android.content.Context
import org.json.JSONObject

/** Developer API identity, not account authorization. Its toString never reveals the hash. */
class TelegramConfiguration(val apiId: Int, val apiHash: String) {
    override fun toString() = "TelegramConfiguration([redacted])"
    companion object {
        fun load(context: Context): TelegramConfiguration {
            val json = context.assets.open("telegram-config.json").bufferedReader().use { JSONObject(it.readText()) }
            val id = json.optInt("apiId")
            val hash = json.optString("apiHash")
            check(id > 0 && hash.matches(Regex("[a-fA-F0-9]{32}"))) { "Missing API configuration" }
            return TelegramConfiguration(id, hash)
        }
    }
}
