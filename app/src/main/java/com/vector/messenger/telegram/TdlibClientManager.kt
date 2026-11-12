package com.vector.messenger.telegram

import android.content.Context
import android.os.Build
import android.util.Base64
import com.vector.core.auth.*
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.JsonClient
import org.json.JSONObject

/** One process-owned TDLib client and exactly one blocking receive loop. Composables never access JNI. */
class TdlibClientManager(private val context: Context, private val scope: CoroutineScope) : TelegramAuthClient {
    private val events = Channel<AuthStep>(Channel.UNLIMITED)
    override val steps = events.receiveAsFlow()
    private val lifecycle = Mutex()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val vault = TdlibKeyVault(context)
    private val database = File(context.noBackupFilesDir, "telegram/database")
    private val files = File(context.noBackupFilesDir, "telegram/files")
    @Volatile private var clientId = 0
    @Volatile private var logoutConfirmed = false
    private var receiver: Job? = null

    override suspend fun start() = withContext(Dispatchers.IO) {
        lifecycle.withLock {
            if (clientId != 0) return@withLock
            val configuration = try { TelegramConfiguration.load(context) } catch (_: Exception) {
                events.send(AuthStep.SetupRequired("Add your Telegram API ID and hash to the ignored telegram.properties file, then rebuild. See docs/TDLIB_AUTH.md.")); return@withLock
            }
            try {
                // Only synchronous documented functions are used with execute. Suppress native logs before any credentials.
                JsonClient.execute(request("setLogStream", "log_stream" to request("logStreamEmpty")).toString())
                JsonClient.execute(request("setLogVerbosityLevel", "new_verbosity_level" to 0).toString())
                clientId = JsonClient.createClientId()
            } catch (_: LinkageError) {
                events.send(AuthStep.SetupRequired("The official TDLib native library is unavailable for this device. Build/import the verified Android library described in docs/TDLIB_AUTH.md.")); return@withLock
            }
            logoutConfirmed = false
            val id = clientId
            receiver = scope.launch(Dispatchers.IO) {
                try {
                    while (isActive && clientId == id) {
                        val raw = JsonClient.receive(.5) ?: continue // Blocking official TDLib receive, not busy polling.
                        val json = JSONObject(raw)
                        if (json.optInt("@client_id") != id) continue
                        val extra = json.optLong("@extra", -1)
                        if (extra != -1L) { pending.remove(extra)?.complete(json); continue }
                        if (json.optString("@type") != "updateAuthorizationState") continue
                        val auth = json.getJSONObject("authorization_state")
                        when (auth.optString("@type")) {
                            "authorizationStateLoggingOut" -> { logoutConfirmed = true; events.send(AuthStep.LoggingOut) }
                            "authorizationStateReady" -> { logoutConfirmed = false; events.send(AuthStep.Ready) }
                            "authorizationStateWaitTdlibParameters" -> scope.launch(Dispatchers.IO) { initialize(configuration) }
                            "authorizationStateClosed" -> {
                                lifecycle.withLock {
                                    clientId = 0
                                    pending.values.forEach { it.cancel() }; pending.clear()
                                    if (logoutConfirmed) {
                                        try { vault.clearAfterLogout() }
                                        catch (_: Exception) { events.send(AuthStep.SetupRequired("Logout completed, but local key cleanup failed. Restart the app before signing in again.")); return@launch }
                                    }
                                    events.send(AuthStep.Closed)
                                }
                            }
                            else -> events.send(mapAuthorization(auth))
                        }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { events.send(AuthStep.SetupRequired("Telegram stopped unexpectedly. Restart the application; existing session data has been preserved.")) }
            }
            // The first asynchronous request starts TDLib update delivery; auth updates remain the source of truth.
            JsonClient.send(id, request("getAuthorizationState").toString())
        }
    }

    private suspend fun initialize(configuration: TelegramConfiguration) {
        try {
            val key = vault.loadOrCreate(database)
            try {
                check(database.exists() || database.mkdirs())
                check(files.exists() || files.mkdirs())
                val parameters = request("setTdlibParameters",
                    "use_test_dc" to false, "database_directory" to database.absolutePath, "files_directory" to files.absolutePath,
                    "database_encryption_key" to Base64.encodeToString(key, Base64.NO_WRAP),
                    "use_file_database" to true, "use_chat_info_database" to true, "use_message_database" to true,
                    "use_secret_chats" to false,
                    "api_id" to configuration.apiId, "api_hash" to configuration.apiHash,
                    "system_language_code" to Locale.getDefault().toLanguageTag(), "device_model" to Build.MODEL,
                    "system_version" to Build.VERSION.RELEASE, "application_version" to "DEFAULT 0.1.0")
                val result = send(parameters)
                if (result.optString("@type") == "error") events.send(AuthStep.SetupRequired("Telegram initialization was rejected. Check the API configuration and original storage key; existing data has been preserved."))
            } finally { key.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { events.send(AuthStep.SetupRequired(AuthFailure.Storage.description)) }
    }

    override suspend fun execute(action: AuthAction): AuthFailure? = withContext(Dispatchers.IO) {
        if (clientId == 0) return@withContext AuthFailure.Unavailable
        try {
            val result = send(action.toRequest())
            if (result.optString("@type") == "error") {
                mapTdlibFailure(result)
            } else null
        } catch (_: TimeoutCancellationException) { AuthFailure.Network }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { AuthFailure.Unavailable }
    }

    private suspend fun send(payload: JSONObject): JSONObject {
        val id = ids.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        return try {
            JsonClient.send(clientId, payload.put("@extra", id).toString())
            withTimeout(45_000) { deferred.await() }
        } finally { pending.remove(id) }
    }
}
