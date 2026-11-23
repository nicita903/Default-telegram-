package com.vector.messenger.notifications

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.vector.core.profile.ProfileConnection
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.telegram.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.json.JSONArray
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Singleton
class TelegramPushRegistration @Inject constructor(@ApplicationContext private val context: Context,
    private val manager: TdlibClientManager, private val profile: ProfileRepository, private val scope: CoroutineScope) {
    private val storage = context.getSharedPreferences("telegram_push_registration", Context.MODE_PRIVATE)
    private val tokens = MutableStateFlow<String?>(null)
    private val reconcile = MutableStateFlow(0L)
    private val mutable = MutableStateFlow("Firebase configuration required")
    val state = mutable.asStateFlow()
    private val attempts = mutableMapOf<String, Long>()
    private var started = false
    private var firebase: FirebaseApp? = null
    private var hadAccount = false
    private var observedAccount: Long? = null
    fun start() {
        if (started) return
        started = true
        scope.launch {
            profile.state.map { Triple(if (it.authenticated) it.profile?.id else null, it.connection, it.authenticated) }
                .distinctUntilChanged().collect { (account, _, authenticated) ->
                    if (account != null) {
                        hadAccount = true; observedAccount = account
                        withContext(Dispatchers.IO) { storage.edit().putLong("current", account).commit() }
                    } else if (hadAccount && !authenticated) {
                        val previous = observedAccount
                        withContext(Dispatchers.IO) {
                            val edit = storage.edit().remove("current")
                            if (previous != null) edit.remove("telegram:$previous:tokenHash").remove("telegram:$previous:receiver").remove("telegram:$previous:confirmedAt")
                            edit.commit()
                        }
                        hadAccount = false; observedAccount = null
                    }
                }
        }
        scope.launch {
            combine(profile.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged(), tokens,
                profile.state.map { it.connection == ProfileConnection.Ready }.distinctUntilChanged(), reconcile) { account, token, connected, _ -> Triple(account, token, connected) }
                .collect { (account, token, _) ->
                    if (account == null || token == null) return@collect
                    val hash = digest(token)
                    val prefix = "telegram:$account:"
                    val saved = withContext(Dispatchers.IO) {
                        storage.getString(prefix + "tokenHash", null) == hash && storage.getLong(prefix + "receiver", 0) != 0L &&
                            System.currentTimeMillis() - storage.getLong(prefix + "confirmedAt", 0) < TimeUnit.DAYS.toMillis(1)
                    }
                    if (saved) { mutable.value = "Registered; delivery verification required"; return@collect }
                    val attempt = "$account:$hash"
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - (attempts[attempt] ?: -TimeUnit.MINUTES.toMillis(5)) < TimeUnit.MINUTES.toMillis(5)) return@collect
                    attempts[attempt] = now
                    PushDiagnostics.event("TDLib push registration attempted")
                    mutable.value = "Registering Telegram push"
                    try {
                        val response = manager.messagingRequest(request("registerDevice", "device_token" to request("deviceTokenFirebaseCloudMessaging", "token" to token, "encrypt" to true), "other_user_ids" to JSONArray()), account)
                        check(response.optString("@type") == "pushReceiverId")
                        val receiver = response.exactLong("id")
                        check(receiver != 0L && profile.state.value.profile?.id == account && profile.state.value.authenticated)
                        withContext(Dispatchers.IO) {
                            check(storage.edit().putString(prefix + "tokenHash", hash).putLong(prefix + "receiver", receiver)
                                .putLong(prefix + "confirmedAt", System.currentTimeMillis()).putLong("current", account).commit())
                        }
                        mutable.value = "Registered; delivery verification required"
                        PushDiagnostics.event("TDLib push registration succeeded")
                    } catch (cancelled: CancellationException) { throw cancelled }
                      catch (_: Exception) { mutable.value = "Push registration failed; will retry on a later connection/resume"; PushDiagnostics.event("TDLib push registration failed") }
                }
        }
        scope.launch(Dispatchers.IO) { initializeFirebase() }
    }
    private fun initializeFirebase() {
        try {
            firebase = FirebaseApp.getApps(context).firstOrNull { it.name == FirebaseApp.DEFAULT_APP_NAME }
                ?: FirebaseApp.initializeApp(context)
            if (firebase == null) {
                val config = context.assets.open("firebase-client.json").bufferedReader().use { JSONObject(it.readText()) }
                if (config.length() == 0) { PushDiagnostics.event("Firebase configuration missing"); return }
                firebase = FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(config.getString("applicationId"))
                    .setApiKey(config.getString("apiKey")).setProjectId(config.getString("projectId")).setGcmSenderId(config.getString("senderId")).build())
            }
            PushDiagnostics.event("Firebase initialized")
            FirebaseMessaging.getInstance().apply {
                isAutoInitEnabled = true
                token.addOnSuccessListener { token(it) }.addOnFailureListener { mutable.value = "FCM token unavailable"; PushDiagnostics.event("FCM token unavailable") }
            }
        } catch (_: Exception) { mutable.value = "Firebase configuration unavailable"; PushDiagnostics.event("Firebase initialization failed") }
          catch (_: LinkageError) { mutable.value = "Firebase unavailable"; PushDiagnostics.event("Firebase dependency unavailable") }
    }
    fun token(value: String) { tokens.value = value; PushDiagnostics.event("FCM token available") }
    fun foreground() { reconcile.update { it + 1 } }
    suspend fun target(receiver: Long): Long? = withContext(Dispatchers.IO) {
        val current = storage.getLong("current", 0).takeIf { it != 0L } ?: return@withContext null
        if (receiver == 0L) return@withContext current
        // One currently supported session. A future account registry can dispatch other known receivers.
        current.takeIf { storage.getLong("telegram:$it:receiver", 0) == receiver }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
