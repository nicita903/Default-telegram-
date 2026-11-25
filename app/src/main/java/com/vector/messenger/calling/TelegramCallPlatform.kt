package com.vector.messenger.calling

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.graphics.drawable.IconCompat
import androidx.core.telecom.*
import com.vector.messenger.MainActivity
import com.vector.messenger.feature.media.MediaImages
import com.vector.messenger.telegram.*
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.hilt.android.EntryPointAccessors
import javax.inject.Inject
import javax.inject.Singleton
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

internal data class PlatformCall(val account: Long, val id: Int, val attempt: Long, val epoch: Long)

/** Android owns call concurrency/routing; TDLib and tgcalls remain the only signaling/media engine. */
@Singleton
class TelegramCallPlatform @Inject constructor(@ApplicationContext private val context: Context,
    private val scope: CoroutineScope, private val avatars: TdlibAvatarClient) {
    companion object {
        const val ID = 8400
        const val OPEN = "com.vector.messenger.CALL_OPEN"
        const val ANSWER = "com.vector.messenger.CALL_ANSWER"
        const val END = "com.vector.messenger.CALL_END"
        private const val RING = "telegram_incoming_calls_v1"
        private const val ONGOING = "default_voice_calls"
        private const val MISSED = "telegram_missed_calls_v1"
    }
    private class Entry(val key: PlatformCall, val nonce: String) {
        val ready = CompletableDeferred<Unit>()
        var control: CallControlScope? = null
        var job: Job? = null
        var avatarJob: Job? = null
        var endpoints = emptyList<CallEndpointCompat>()
        var person: Person? = null
        var ringing = false
    }
    private lateinit var calls: TelegramVoiceCalls
    private val system = context.getSystemService(NotificationManager::class.java)
    private val manager by lazy { CallsManager(context) }
    private var registered = false
    private var entry: Entry? = null
    private var closing: Job? = null
    fun attach(controller: TelegramVoiceCalls) {
        calls = controller; channels()
        // A prior process's local TDLib call IDs are not identities in this process.
        // New authenticated updateCall events will publish fresh notifications/capabilities.
        try { system.cancel(ID) } catch (_: Exception) { callDiagnostic(context, CallEvent.PlatformFailure) }
    }
    private fun channels() {
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
        system.createNotificationChannel(NotificationChannel(RING, "Incoming Telegram calls", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs); enableVibration(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        })
        system.createNotificationChannel(NotificationChannel(ONGOING, "Voice calls", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        system.createNotificationChannel(NotificationChannel(MISSED, "Missed Telegram calls", NotificationManager.IMPORTANCE_DEFAULT))
    }
    internal fun current(key: PlatformCall) = entry?.key == key && calls.matches(key)
    internal suspend fun ensure(key: PlatformCall) {
        entry?.takeIf { it.key == key }?.let { it.ready.await(); return }
        check(entry == null && calls.matches(key))
        val value = Entry(key, UUID.randomUUID().toString()); entry = value
        val previous = closing
        value.job = scope.launch {
            try {
                previous?.join()
                if (!registered) { withContext(Dispatchers.IO) { manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }; registered = true }
                if (!current(key)) return@launch
                val initial = calls.state.value
                val attributes = CallAttributesCompat(displayName = initial.name.ifBlank { "Telegram caller" },
                    address = Uri.parse("default-call:${key.id}"),
                    direction = if (initial.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL, callCapabilities = 0)
                manager.addCall(attributes,
                    onAnswer = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.answerFromTelecom(key) } },
                    onDisconnect = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.end() } },
                    onSetActive = { /* Never synthesize a media Established event. */ },
                    onSetInactive = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.end("Call interrupted by another system call.") } }) {
                    value.control = this
                    if (!current(key)) { launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }; return@addCall }
                    callDiagnostic(context, CallEvent.TelecomRegistered)
                    post(value, calls.state.value)
                    value.ready.complete(Unit)
                    launch { currentCallEndpoint.collect { endpoint -> if (current(key)) calls.telecomEndpoint(endpoint.type) } }
                    launch { availableEndpoints.collect { value.endpoints = it } }
                    launch { isMuted.collect { if (current(key)) calls.systemMute(it) } }
                    launch {
                        var established = false
                        calls.state.collect { state ->
                            if (!current(key)) return@collect
                            post(value, state)
                            if (state.phase == VoiceCallPhase.Active && !established) {
                                established = true
                                if (initial.outgoing && setActive() is CallControlResult.Error) calls.end("Android could not activate this call.")
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                callDiagnostic(context, CallEvent.PlatformFailure)
                if (current(key)) calls.end("Android could not register this call.")
            } finally {
                if (!value.ready.isCompleted) value.ready.completeExceptionally(IllegalStateException("Call registration unavailable"))
                if (entry === value && calls.matches(key)) calls.end("Android call session ended.")
            }
        }
        value.ready.await()
    }
    internal suspend fun answer(key: PlatformCall): Boolean {
        ensure(key)
        val value = entry?.takeIf { it.key == key } ?: return false
        return value.control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL) is CallControlResult.Success
    }
    internal fun route(type: Int) {
        val value = entry ?: return
        val endpoint = value.endpoints.firstOrNull { it.type == type } ?: return
        scope.launch {
            try { if (current(value.key)) value.control?.requestEndpointChange(endpoint) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { callDiagnostic(context, CallEvent.PlatformFailure) }
        }
    }
    internal fun close(cause: Int = DisconnectCause.LOCAL) {
        val old = entry ?: return; entry = null
        old.ready.cancel(); old.avatarJob?.cancel()
        try { system.cancel(ID) } catch (_: Exception) { callDiagnostic(context, CallEvent.PlatformFailure) }
        closing = scope.launch {
            try { withTimeoutOrNull(3_000) { old.control?.disconnect(DisconnectCause(cause)) } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { callDiagnostic(context, CallEvent.PlatformFailure) }
            finally { withContext(NonCancellable) { old.job?.cancelAndJoin() } }
        }
    }
    private fun intent(value: Entry, action: String, activity: Boolean): PendingIntent {
        val target = if (activity) Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            else Intent(context, CallActionReceiver::class.java)
        target.action = action
        target.data = Uri.parse("default-call-action://${value.nonce}/$action")
        target.putExtra("call_account", value.key.account).putExtra("call_id", value.key.id).putExtra("call_nonce", value.nonce)
        return if (activity) PendingIntent.getActivity(context, 8400, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getBroadcast(context, 8400, target, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
    fun issued(intent: Intent): Boolean = intent.action in setOf(OPEN, ANSWER, END) && validates(intent)
    internal fun validates(intent: Intent): Boolean {
        val value = entry ?: return false
        return current(value.key) && intent.getLongExtra("call_account", 0) == value.key.account && intent.getIntExtra("call_id", 0) == value.key.id &&
            intent.getStringExtra("call_nonce") == value.nonce
    }
    private fun notification(value: Entry, state: VoiceCallState): Notification {
        val ringing = state.phase == VoiceCallPhase.Incoming
        val person = value.person?.takeIf { it.name == state.name } ?: Person.Builder().setName(state.name.ifBlank { "Telegram caller" }).setImportant(true).build()
        val open = intent(value, OPEN, true)
        val end = intent(value, END, false)
        val style = if (ringing) NotificationCompat.CallStyle.forIncomingCall(person, end, intent(value, ANSWER, true))
            else NotificationCompat.CallStyle.forOngoingCall(person, end)
        val builder = NotificationCompat.Builder(context, if (ringing) RING else ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(state.name.ifBlank { "Telegram voice call" })
            .setContentText(if (ringing) "Incoming voice call" else when (state.phase) {
                VoiceCallPhase.Active -> "Voice call in progress"; VoiceCallPhase.Reconnecting -> "Reconnecting…"; else -> "Connecting…" })
            .setStyle(style).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
        if (ringing && (Build.VERSION.SDK_INT < 34 || system.canUseFullScreenIntent())) builder.setFullScreenIntent(open, true)
        return builder.build().apply { if (ringing) flags = flags or Notification.FLAG_INSISTENT }
    }
    private fun post(value: Entry, state: VoiceCallState) {
        if (!current(value.key)) return
        if (value.ringing && state.phase != VoiceCallPhase.Incoming) system.cancel(ID)
        val newlyRinging = !value.ringing && state.phase == VoiceCallPhase.Incoming
        value.ringing = state.phase == VoiceCallPhase.Incoming
        system.notify(ID, notification(value, state))
        if (newlyRinging) callDiagnostic(context, CallEvent.IncomingPosted)
    }
    internal fun foregroundNotification(): Notification? = entry?.takeIf { current(it.key) }?.let { notification(it, calls.state.value) }
    internal fun refreshIdentity(key: PlatformCall) {
        val value = entry?.takeIf { it.key == key } ?: return
        value.avatarJob?.cancel()
        value.avatarJob = scope.launch {
            try {
                val identity = avatars.user(avatars.call(request("getUser", "user_id" to calls.state.value.user), key.account))
                val photo = identity.small ?: return@launch
                val file = avatars.download(photo.fileId, key.account)
                val complete = if (file.optJSONObject("local")?.optBoolean("is_downloading_completed") == true) file else withTimeoutOrNull(3_000) {
                    avatars.updates.mapNotNull { it.optJSONObject("file") }.first { it.optInt("id") == photo.fileId && it.optJSONObject("local")?.optBoolean("is_downloading_completed") == true }
                } ?: return@launch
                val path = complete.optJSONObject("local")?.optString("path")?.takeIf { it.isNotBlank() } ?: return@launch
                val bitmap = MediaImages.load(path, 128, key.account)?.bitmap ?: return@launch
                if (current(key)) {
                    value.person = Person.Builder().setName(calls.state.value.name.ifBlank { identity.name })
                        .setImportant(true).setIcon(IconCompat.createWithBitmap(bitmap)).build()
                    post(value, calls.state.value)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Caller photo is optional and must never delay ringing. */ }
        }
    }
    internal fun missed(key: PlatformCall, state: VoiceCallState) {
        scope.launch {
            try {
                val chat = avatars.call(request("createPrivateChat", "user_id" to state.user, "force" to false), key.account).getLong("id")
                if (!calls.sameAccount(key.account)) return@launch
                val open = Intent(context, MainActivity::class.java).setAction("com.vector.messenger.OPEN_CONVERSATION")
                    .setData(Uri.parse("default-missed:${key.account}:${key.id}")).putExtra("account", key.account).putExtra("chat", chat)
                val pending = PendingIntent.getActivity(context, 8401, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                system.notify("default:missed:${key.account}:${key.id}", 8401, NotificationCompat.Builder(context, MISSED)
                    .setSmallIcon(android.R.drawable.stat_notify_missed_call).setContentTitle(state.name.ifBlank { "Telegram caller" })
                    .setContentText("Missed voice call").setWhen(System.currentTimeMillis()).setShowWhen(true)
                    .setContentIntent(pending).setAutoCancel(true).setCategory(NotificationCompat.CATEGORY_MISSED_CALL).build())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Real TDLib call history remains authoritative. */ }
        }
    }
}

@EntryPoint @InstallIn(SingletonComponent::class)
interface CallEntryPoint { fun calls(): TelegramVoiceCalls }
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelegramCallPlatform.END) return
        val pending = goAsync()
        val controller = EntryPointAccessors.fromApplication(context.applicationContext, CallEntryPoint::class.java).calls()
        controller.notificationAction(intent) { pending.finish() }
    }
}
