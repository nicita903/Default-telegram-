package com.vector.messenger.notifications

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.security.MessageDigest
import java.util.UUID

/** Only fixed stages, transport state and per-process account aliases. Never payloads/tokens. */
internal object PushDiagnostics {
    private val salt = UUID.randomUUID().toString()
    @Volatile private var account = "unknown"
    @Volatile private var foreground = false
    @Volatile private var network = "unknown"
    fun install(context: Context) {
        try {
            val manager = context.getSystemService(ConnectivityManager::class.java)
            val caps = manager.activeNetwork?.let(manager::getNetworkCapabilities)
            network(if (caps == null) "none" else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "wifi" else "other",
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        } catch (_: Exception) { network = "unknown" }
    }
    fun account(id: Long?) {
        account = if (id == null || id == 0L) "none" else MessageDigest.getInstance("SHA-256")
            .digest("$salt:$id".toByteArray()).take(4).joinToString("") { "%02x".format(it) }
    }
    fun lifecycle(visible: Boolean) { foreground = visible; event(if (visible) "APP_FOREGROUND" else "APP_BACKGROUND") }
    fun network(type: String, validated: Boolean) { network = "$type/validated=$validated" }
    fun failure(stage: String, error: Throwable, correlation: String = "session") {
        // SDK error messages can contain tokens or URLs. Preserve class and known constant codes only.
        val symbol = error.message?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "redacted"
        event("$stage exception=${error.javaClass.simpleName} reason=$symbol", correlation)
    }
    fun telegramFailure(stage: String, response: org.json.JSONObject, correlation: String = "session") {
        val symbol = response.optString("message").takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{0,95}")) } ?: "redacted"
        event("$stage code=${response.optInt("code")} reason=$symbol", correlation)
    }
    fun event(stage: String, correlation: String = "session") {
        Log.i("DefaultPush", "t=${SystemClock.elapsedRealtime()} pid=${Process.myPid()} account=$account network=$network fg=$foreground ref=${correlation.take(12)} $stage")
    }
}
