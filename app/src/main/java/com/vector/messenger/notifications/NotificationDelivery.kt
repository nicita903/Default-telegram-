package com.vector.messenger.notifications

import android.content.*
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.core.app.RemoteInput
import androidx.work.*
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

@EntryPoint @InstallIn(SingletonComponent::class)
interface NotificationEntryPoint { fun notifications(): TelegramNotifications }
private fun notifications(context: Context) = EntryPointAccessors.fromApplication(context.applicationContext, NotificationEntryPoint::class.java).notifications()

/** A bounded, encrypted work handoff. WorkManager only stores an opaque filename, never codes, tokens or reply text. */
internal object NotificationWork {
    private const val ALIAS = "default.notification.handoff.v1"
    @Synchronized private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey(ALIAS, null) as? SecretKey) ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun directory(context: Context) = File(context.noBackupFilesDir, "notification_work").apply { mkdirs() }
    private fun ledger(context: Context) = context.getSharedPreferences("notification_work_receipts", Context.MODE_PRIVATE)
    private data class Gate(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val gates = mutableMapOf<String, Gate>()
    fun correlation(event: String): String = MessageDigest.getInstance("SHA-256").digest(event.toByteArray()).joinToString("") { "%02x".format(it) }
    @Synchronized fun enqueue(context: Context, payload: JSONObject, event: String = UUID.randomUUID().toString(), expedited: Boolean = true): File? {
        val id = correlation(event)
        if (id in ledger(context).getStringSet("completed", emptySet()).orEmpty()) return null
        val directory = directory(context)
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
        val file = File(directory, id)
        if (!file.exists()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            val atomic = android.util.AtomicFile(file)
            var stream: java.io.FileOutputStream? = null
            try {
                val encrypted = cipher.doFinal(bytes)
                val output = atomic.startWrite(); stream = output
                output.write(cipher.iv.size); output.write(cipher.iv); output.write(encrypted)
                atomic.finishWrite(output); stream = null
            } catch (failure: Exception) { stream?.let(atomic::failWrite); throw failure }
            finally { bytes.fill(0) }
        }
        val builder = OneTimeWorkRequestBuilder<NotificationDeliveryWorker>().setInputData(workDataOf("file" to id))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
        if (expedited && android.os.Build.VERSION.SDK_INT >= 31) builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork("notification:$id", ExistingWorkPolicy.KEEP, builder.build())
        PushDiagnostics.event(if (expedited) "expedited work enqueued; quota fallback allowed" else "ordinary work enqueued; Android may defer", id)
        return file
    }
    suspend fun deliver(context: Context, file: File): Boolean {
        val gate = synchronized(gates) { gates.getOrPut(file.name) { Gate() }.also { it.users++ } }
        try { return gate.mutex.withLock {
            val receipts = ledger(context)
            if (!file.exists() || file.name in receipts.getStringSet("completed", emptySet()).orEmpty()) return@withLock true
            val value = read(file)
            val host = notifications(context)
            when (value.optString("operation")) {
                "push" -> if (!host.push(value.getString("payload"), file.name.take(12))) return@withLock false
                "action" -> {
                    val intent = Intent().setAction(value.optString("action")).putExtra("account", value.optLong("account"))
                        .putExtra("chat", value.optLong("chat")).putExtra("nonce", value.optString("nonce"))
                    val ids = value.optJSONArray("messages")
                    intent.putExtra("messages", LongArray(ids?.length() ?: 0) { ids!!.optLong(it) })
                    val groups = value.optJSONArray("groups")
                    intent.putExtra("groups", IntArray(groups?.length() ?: 0) { groups!!.optInt(it) })
                    if (value.has("reply")) RemoteInput.addResultsToIntent(arrayOf(RemoteInput.Builder("reply").build()), intent,
                        android.os.Bundle().apply { putCharSequence("reply", value.getString("reply")) })
                    host.action(intent)
                }
                else -> { file.delete(); return@withLock true }
            }
            val completed = (receipts.getStringSet("completed", emptySet()).orEmpty().toList().takeLast(255) + file.name).toSet()
            if (!receipts.edit().putStringSet("completed", completed).commit()) return@withLock false
            file.delete()
            true
        } } finally { synchronized(gates) { gate.users--; if (gate.users == 0) gates.remove(file.name) } }
    }
    fun file(context: Context, name: String?): File? = name?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(directory(context), it) }
    fun read(file: File): JSONObject {
        val bytes = android.util.AtomicFile(file).readFully()
        try {
            require(bytes.isNotEmpty() && bytes.size <= 16 * 1024)
            val ivSize = bytes[0].toInt() and 255
            require(ivSize in 12..16 && bytes.size > ivSize + 17)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, ivSize + 1))) }
            val plain = cipher.doFinal(bytes, ivSize + 1, bytes.size - ivSize - 1)
            return try { JSONObject(plain.toString(Charsets.UTF_8)) } finally { plain.fill(0) }
        } finally { bytes.fill(0) }
    }
}

class NotificationDeliveryWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val file = NotificationWork.file(applicationContext, inputData.getString("file")) ?: return Result.failure()
        if (!file.exists()) return Result.success()
        try {
            PushDiagnostics.event("background worker started", file.name)
            val delivered = withTimeoutOrNull(110_000) { NotificationWork.deliver(applicationContext, file) } ?: false
            if (delivered) return Result.success()
            PushDiagnostics.event("background processing incomplete", file.name)
            if (runAttemptCount < 3) return Result.retry()
            file.delete(); return Result.failure()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            PushDiagnostics.event("background processing failed", file.name)
            if (runAttemptCount < 3) return Result.retry()
            file.delete(); return Result.failure()
        }
    }
}

class NotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf("reply", "read", "mute", "dismiss")) return
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val value = JSONObject().put("operation", "action").put("action", intent.action)
                    .put("account", intent.getLongExtra("account", 0)).put("chat", intent.getLongExtra("chat", 0))
                    .put("nonce", intent.getStringExtra("nonce")).put("messages", JSONArray(intent.getLongArrayExtra("messages")?.toList().orEmpty()))
                    .put("groups", JSONArray(intent.getIntArrayExtra("groups")?.toList().orEmpty()))
                RemoteInput.getResultsFromIntent(intent)?.getCharSequence("reply")?.toString()?.take(4097)?.let { value.put("reply", it) }
                NotificationWork.enqueue(context, value, "action:${intent.getStringExtra("nonce")}")
            } catch (_: Exception) { /* Never log action payloads. */ }
              finally { result.finish(); coroutineContext.cancelChildren() }
        }
    }
}

class DefaultPushService : FirebaseMessagingService() {
    override fun onNewToken(token: String) { notifications(this).token(token) }
    override fun onMessageReceived(message: RemoteMessage) {
        val event = "push:${message.messageId ?: UUID.randomUUID()}"
        val correlation = NotificationWork.correlation(event).take(12)
        val high = message.priority == RemoteMessage.PRIORITY_HIGH
        PushDiagnostics.event("FCM push received priorityHigh=$high", correlation)
        try {
            val payload = JSONObject(message.data as Map<*, *>).put("google.sent_time", message.sentTime)
            message.notification?.sound?.let { payload.put("google.notification.sound", it) }
            val file = NotificationWork.enqueue(this, JSONObject().put("operation", "push").put("payload", payload.toString()),
                event, expedited = high) ?: return
            // FCM runs this callback off Main. Persist first, then spend at most four seconds
            // on immediate TDLib processing (eight for high-priority push); the same per-event gate protects the worker race.
            runBlocking(Dispatchers.IO) { withTimeoutOrNull(if (high) 8_000L else 4_000L) { NotificationWork.deliver(applicationContext, file) } }
        } catch (_: Exception) { PushDiagnostics.event("FCM handoff failed", correlation) }
          catch (_: LinkageError) { PushDiagnostics.event("FCM native dependency unavailable", correlation) }
    }
}
