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
    private val mutable = MutableStateFlow("Firebase configuration required")
    val state = mutable.asStateFlow()
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
                profile.state.map { it.connection == ProfileConnection.Ready }.distinctUntilChanged()) { account, token, connected -> Triple(account, token, connected) }
                .distinctUntilChanged().collectLatest { (account, token, connected) ->
                    if (account == null || token == null || !connected) return@collectLatest
                    val hash = digest(token)
                    val prefix = "telegram:$account:"
                    var backoff = 15_000L
                    while (currentCoroutineContext().isActive) {
                        val confirmedAt = withContext(Dispatchers.IO) {
                            if (storage.getString(prefix + "tokenHash", null) == hash && storage.getLong(prefix + "receiver", 0) != 0L)
                                storage.getLong(prefix + "confirmedAt", 0) else 0L
                        }
                        val remaining = TimeUnit.DAYS.toMillis(1) - (System.currentTimeMillis() - confirmedAt)
                        if (confirmedAt > 0 && remaining in 1..TimeUnit.DAYS.toMillis(1)) {
                            mutable.value = "Registered; delivery verification required"
                            delay(remaining)
                            continue
                        }
                        PushDiagnostics.event("TDLIB_REGISTER_DEVICE_REQUESTED")
                        mutable.value = "Registering Telegram push"
                        try {
                            val response = withTimeout(30_000) { manager.messagingRequest(request("registerDevice", "device_token" to request("deviceTokenFirebaseCloudMessaging", "token" to token, "encrypt" to true), "other_user_ids" to JSONArray()), account) }
                            if (response.optString("@type") == "error") {
                                PushDiagnostics.telegramFailure("TDLIB_REGISTER_DEVICE_FAILED", response)
                                mutable.value = "Telegram push registration failed (${response.optInt("code")}); check Firebase/Telegram configuration"
                                // A bad application key/token requires configuration or a new token, not a retry loop.
                                if (response.optInt("code") in listOf(400, 401, 403)) return@collectLatest
                            } else {
                                check(response.optString("@type") == "pushReceiverId")
                                val receiver = response.exactLong("id")
                                check(receiver != 0L && profile.state.value.profile?.id == account && profile.state.value.authenticated && tokens.value == token)
                                withContext(Dispatchers.IO) {
                                    check(storage.edit().putString(prefix + "tokenHash", hash).putLong(prefix + "receiver", receiver)
                                        .putLong(prefix + "confirmedAt", System.currentTimeMillis()).putLong("current", account).commit())
                                }
                                mutable.value = "Registered; delivery verification required"
                                PushDiagnostics.event("TDLIB_REGISTER_DEVICE_SUCCEEDED")
                                backoff = 15_000L
                                continue
                            }
                        } catch (timeout: TimeoutCancellationException) {
                            mutable.value = "Push registration timed out; retry scheduled"
                            PushDiagnostics.failure("TDLIB_REGISTER_DEVICE_FAILED", timeout)
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (failure: Exception) {
                            mutable.value = "Push registration unavailable; retry scheduled"
                            PushDiagnostics.failure("TDLIB_REGISTER_DEVICE_FAILED", failure)
                        }
                        // One cancellable retry job per authorized account/token/connection. No Activity dependency.
                        PushDiagnostics.event("TDLIB_REGISTER_DEVICE_RETRY_SCHEDULED delayMs=$backoff")
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(300_000L)
                    }
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
                if (config.length() == 0) { mutable.value = "Firebase not configured: add app/google-services.json"; PushDiagnostics.event("FIREBASE_CONFIGURATION_MISSING source=app/google-services.json"); return }
                firebase = FirebaseApp.initializeApp(context, FirebaseOptions.Builder().setApplicationId(config.getString("applicationId"))
                    .setApiKey(config.getString("apiKey")).setProjectId(config.getString("projectId")).setGcmSenderId(config.getString("senderId")).build())
            }
            PushDiagnostics.event("Firebase initialized")
            FirebaseMessaging.getInstance().apply {
                isAutoInitEnabled = true
                token.addOnSuccessListener { token(it) }.addOnFailureListener { error -> mutable.value = "FCM token unavailable"; PushDiagnostics.failure("FCM_TOKEN_FAILED", error) }
            }
        } catch (error: Exception) { mutable.value = "Firebase configuration unavailable"; PushDiagnostics.failure("FIREBASE_INITIALIZATION_FAILED", error) }
          catch (_: LinkageError) { mutable.value = "Firebase unavailable"; PushDiagnostics.event("Firebase dependency unavailable") }
    }
    fun token(value: String, refreshed: Boolean = false) { if (value.isBlank()) return; tokens.value = value; PushDiagnostics.event(if (refreshed) "FCM_TOKEN_REFRESHED" else "FCM_TOKEN_AVAILABLE") }
    suspend fun target(receiver: Long): Long? = withContext(Dispatchers.IO) {
        val current = storage.getLong("current", 0).takeIf { it != 0L } ?: return@withContext null
        if (receiver == 0L) return@withContext current
        // One currently supported session. A future account registry can dispatch other known receivers.
        current.takeIf { storage.getLong("telegram:$it:receiver", 0) == receiver }
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
