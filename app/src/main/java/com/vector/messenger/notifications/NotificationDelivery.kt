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
    @Synchronized fun enqueue(context: Context, payload: JSONObject, event: String = UUID.randomUUID().toString()) {
        val id = MessageDigest.getInstance("SHA-256").digest(event.toByteArray()).joinToString("") { "%02x".format(it) }
        val directory = directory(context)
        directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.DAYS.toMillis(1) }?.forEach { it.delete() }
        val file = File(directory, id)
        if (!file.exists()) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val bytes = payload.toString().toByteArray(Charsets.UTF_8)
            try { val encrypted = cipher.doFinal(bytes); file.outputStream().use { it.write(cipher.iv.size); it.write(cipher.iv); it.write(encrypted) } }
            finally { bytes.fill(0) }
        }
        val work = OneTimeWorkRequestBuilder<NotificationDeliveryWorker>().setInputData(workDataOf("file" to id))
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("notification:$id", ExistingWorkPolicy.KEEP, work)
    }
    fun file(context: Context, name: String?): File? = name?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { File(directory(context), it) }
    fun read(file: File): JSONObject {
        val bytes = file.readBytes()
        try {
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
            val value = withContext(Dispatchers.IO) { NotificationWork.read(file) }
            val host = notifications(applicationContext)
            when (value.optString("operation")) {
                "push" -> {
                    if (!host.push(value.getString("payload"))) {
                        if (runAttemptCount < 3) return Result.retry()
                        return Result.failure().also { file.delete() }
                    }
                }
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
            }
            file.delete(); return Result.success()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
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
        // TDLib receives the original data payload, including transport timestamp. No independent FCM UI alert.
        val payload = JSONObject(message.data as Map<*, *>).put("google.sent_time", message.sentTime)
        message.notification?.sound?.let { payload.put("google.notification.sound", it) }
        NotificationWork.enqueue(this, JSONObject().put("operation", "push").put("payload", payload.toString()), "push:${message.messageId ?: UUID.randomUUID()}")
    }
}
