package com.vector.messenger.calling

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.telecom.DisconnectCause
import androidx.core.content.ContextCompat
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
        internal const val RING = "telegram_incoming_calls_v1"
        internal const val DISPLAY = "telegram_incoming_call_display_v1"
        private const val ONGOING = "default_voice_calls"
        private const val MISSED = "telegram_missed_calls_v1"
    }
    private class Entry(val key: PlatformCall, val nonce: String, val outgoing: Boolean) {
        val ready = CompletableDeferred<Unit>()
        var control: CallControlScope? = null
        var job: Job? = null
        var avatarJob: Job? = null
        var endpoints = emptyList<CallEndpointCompat>()
        var endpoint: CallEndpointCompat? = null
        var userRoute: String? = null
        var routeRequest: String? = null
        var expectedRoute: String? = null
        var lastPrivate: String? = null
        var person: Person? = null
        var ringing = false
        var foreground = false
        var operation = "addCall"
    }
    private lateinit var calls: TelegramVoiceCalls
    private val system = context.getSystemService(NotificationManager::class.java)
    private val manager by lazy { CallsManager(context) }
    private var registered = false
    private val registrationLock = Mutex()
    private var entry: Entry? = null
    private var closing: Job? = null
    private var pendingSpeaker: Boolean? = null
    fun attach(controller: TelegramVoiceCalls) {
        calls = controller; channels()
        scope.launch {
            try { register() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { diagnose("registerAppWithTelecom", error = error) }
        }
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
        // High-importance visual channel without FLAG_SILENT/group suppression. Audio has one app owner.
        system.createNotificationChannel(NotificationChannel(DISPLAY, "Incoming call screen", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null); enableVibration(false); lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            description = "Incoming call display. Ringtone and vibration follow Calls settings."
        })
        system.createNotificationChannel(NotificationChannel(ONGOING, "Voice calls", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
        system.createNotificationChannel(NotificationChannel(MISSED, "Missed Telegram calls", NotificationManager.IMPORTANCE_DEFAULT))
    }
    private fun diagnose(operation: String, value: Entry? = entry, error: Throwable? = null,
        code: Int? = null, cancelled: Boolean = false, detail: String = "") {
        telecomDiagnostic(context, operation, registered, value?.outgoing,
            value?.control != null, cancelled, error, code, detail)
    }
    private suspend fun register() = registrationLock.withLock {
        if (registered) return@withLock
        check(entry?.control == null) { "Cannot register during an active Telecom session" }
        diagnose("registerAppWithTelecom.begin")
        withContext(Dispatchers.IO) { manager.registerAppWithTelecom(CallsManager.CAPABILITY_BASELINE) }
        registered = true
        // API 33 exposes only our accounts using the existing MANAGE_OWN_CALLS grant.
        // Earlier Android versions are diagnosed externally rather than requesting phone access.
        if (Build.VERSION.SDK_INT >= 33) try {
            val telecom = context.getSystemService(android.telecom.TelecomManager::class.java)
            diagnose("registerAppWithTelecom.complete", detail = "ownAccountExists=${telecom.ownSelfManagedPhoneAccounts.isNotEmpty()}")
        } catch (error: Exception) { diagnose("inspectOwnPhoneAccount", error = error) }
        else diagnose("registerAppWithTelecom.complete")
    }
    internal fun current(key: PlatformCall) = entry?.key == key && calls.matches(key)
    private suspend fun awaitReady(value: Entry) {
        try { withTimeout(12_000) { value.ready.await() } }
        catch (timeout: TimeoutCancellationException) {
            diagnose("awaitCallReady", value, timeout)
            if (current(value.key)) calls.end("Android call setup timed out.")
            throw timeout
        }
    }
    internal suspend fun ensure(key: PlatformCall) {
        entry?.takeIf { it.key == key }?.let { awaitReady(it); return }
        check(entry == null && calls.matches(key))
        val value = Entry(key, UUID.randomUUID().toString(), calls.state.value.outgoing); entry = value
        val previous = closing
        value.job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                previous?.join()
                value.operation = "registerAppWithTelecom"
                register()
                if (!current(key)) return@launch
                val initial = calls.state.value
                val attributes = CallAttributesCompat(displayName = initial.name.ifBlank { "Telegram caller" },
                    address = Uri.parse("default-call:user/${initial.user}"),
                    direction = if (initial.outgoing) CallAttributesCompat.DIRECTION_OUTGOING else CallAttributesCompat.DIRECTION_INCOMING,
                    callType = CallAttributesCompat.CALL_TYPE_AUDIO_CALL, callCapabilities = 0)
                value.operation = "addCall"
                diagnose(value.operation, value)
                manager.addCall(attributes,
                    onAnswer = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.answerFromTelecom(key) } },
                    onDisconnect = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.end() } },
                    onSetActive = { /* Never synthesize a media Established event. */ },
                    onSetInactive = { withContext(Dispatchers.Main.immediate) { if (current(key)) calls.end("Call interrupted by another system call.") } }) {
                    value.control = this
                    if (!current(key)) { launch { disconnect(DisconnectCause(DisconnectCause.LOCAL)) }; return@addCall }
                    callDiagnostic(context, CallEvent.TelecomRegistered)
                    diagnose("addCall.scopeReady", value)
                    // Ongoing CallStyle cannot be posted with notify() before a foreground
                    // service exists. Let Android promote the real phoneCall service first.
                    value.operation = "startForegroundService"
                    ContextCompat.startForegroundService(context, Intent(context, VoiceCallService::class.java)
                        .putExtra("attempt", key.attempt))
                    launch {
                        combine(currentCallEndpoint, availableEndpoints) { endpoint, available -> endpoint to available }
                            .collect { (endpoint, available) ->
                                if (!current(key)) return@collect
                                val previousEndpoint = value.endpoint
                                val topologyChanged = value.endpoints.map { it.identifier }.toSet() != available.map { it.identifier }.toSet()
                                val expected = value.expectedRoute
                                value.endpoints = available
                                value.endpoint = endpoint
                                val id = endpoint.identifier.toString()
                                if (endpoint.type != CallEndpointCompat.TYPE_SPEAKER) value.lastPrivate = id
                                // A route selected from Android's own call controls is authoritative too.
                                if (expected == id) value.expectedRoute = null
                                if (previousEndpoint != null && previousEndpoint.identifier != endpoint.identifier &&
                                    expected != id && !topologyChanged) value.userRoute = id
                                calls.telecomEndpoint(endpoint.type)
                                if (value.userRoute != null && available.none { it.identifier.toString() == value.userRoute }) value.userRoute = null
                                applyPendingSpeaker(value)
                                if (pendingSpeaker == null && value.userRoute == null && value.routeRequest == null) {
                                    val preferred = privateEndpoint(value) ?: available.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
                                    if (preferred != null && preferred.identifier != endpoint.identifier) requestRoute(value, preferred, manual = false)
                                }
                            }
                    }
                    launch { isMuted.collect { if (current(key)) calls.systemMute(it) } }
                    launch {
                        var established = false
                        calls.state.collect { state ->
                            if (!current(key)) return@collect
                            post(value, state)
                            if (state.phase == VoiceCallPhase.Active && !established) {
                                established = true
                                if (initial.outgoing) {
                                    when (val result = setActive()) {
                                        is CallControlResult.Error -> { diagnose("setActive", value, code = result.errorCode); calls.end("Android could not activate this call.") }
                                        is CallControlResult.Success -> diagnose("setActive.complete", value)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                diagnose(value.operation, value, cancelled, cancelled = true)
                throw cancelled
            } catch (error: Exception) {
                diagnose(value.operation, value, error)
                callDiagnostic(context, CallEvent.PlatformFailure)
                if (!value.ready.isCompleted) value.ready.completeExceptionally(error)
                if (current(key)) calls.end(if (value.control == null) "Android could not register this call." else "Android could not maintain the call notification.")
            } finally {
                if (!value.ready.isCompleted) value.ready.completeExceptionally(IllegalStateException("Call registration unavailable"))
                if (entry === value) {
                    if (calls.matches(key)) calls.end("Android call session ended.")
                    if (entry === value) close()
                }
            }
        }
        value.job!!.start()
        awaitReady(value)
    }
    internal suspend fun answer(key: PlatformCall): Boolean {
        ensure(key)
        val value = entry?.takeIf { it.key == key } ?: return false
        return when (val result = value.control?.answer(CallAttributesCompat.CALL_TYPE_AUDIO_CALL)) {
            is CallControlResult.Success -> true
            is CallControlResult.Error -> { diagnose("answer", value, code = result.errorCode); false }
            else -> false
        }
    }
    private fun privateEndpoint(value: Entry): CallEndpointCompat? =
        listOf(CallEndpointCompat.TYPE_WIRED_HEADSET, CallEndpointCompat.TYPE_BLUETOOTH, CallEndpointCompat.TYPE_EARPIECE)
            .firstNotNullOfOrNull { type -> value.endpoints.firstOrNull { it.type == type } }
    internal fun requestSpeaker(enabled: Boolean) {
        pendingSpeaker = enabled
        entry?.let { applyPendingSpeaker(it) }
    }
    private fun applyPendingSpeaker(value: Entry) {
        val requested = pendingSpeaker ?: return
        if (value.control == null || value.routeRequest != null || !current(value.key)) return
        val target = if (requested) value.endpoints.firstOrNull { it.type == CallEndpointCompat.TYPE_SPEAKER }
            else value.endpoints.firstOrNull { it.identifier.toString() == value.lastPrivate && it.type != CallEndpointCompat.TYPE_SPEAKER }
                ?: privateEndpoint(value)
        if (target == null) {
            if (value.endpoints.isNotEmpty()) { pendingSpeaker = null; calls.speakerRequestFailed() }
            return // Wait for real endpoints; never invent an earpiece on speaker-only hardware.
        }
        pendingSpeaker = null
        requestRoute(value, target, manual = true)
    }
    internal fun privateRoute() {
        val value = entry ?: return
        val endpoint = value.endpoints.firstOrNull { it.identifier.toString() == value.lastPrivate && it.type != CallEndpointCompat.TYPE_SPEAKER }
            ?: privateEndpoint(value) ?: return
        requestRoute(value, endpoint, manual = true)
    }
    internal fun route(type: Int) {
        val value = entry ?: return
        val endpoint = value.endpoints.firstOrNull { it.type == type } ?: return
        requestRoute(value, endpoint, manual = true)
    }
    private fun requestRoute(value: Entry, endpoint: CallEndpointCompat, manual: Boolean) {
        if (!current(value.key) || value.routeRequest != null) return
        val id = endpoint.identifier.toString()
        value.routeRequest = id
        value.expectedRoute = id
        scope.launch {
            try {
                if (current(value.key)) when (val result = value.control?.requestEndpointChange(endpoint)) {
                    is CallControlResult.Error -> {
                        value.expectedRoute = null
                        diagnose("requestEndpointChange", value, code = result.errorCode)
                        if (manual && current(value.key)) calls.speakerRequestFailed()
                    }
                    is CallControlResult.Success -> if (manual) value.userRoute = id
                    else -> Unit
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                value.expectedRoute = null; diagnose("requestEndpointChange", value, error)
                if (manual && current(value.key)) calls.speakerRequestFailed()
            }
            finally { value.routeRequest = null }
            if (current(value.key)) applyPendingSpeaker(value)
            // The endpoint flow alone updates the UI; a successful request isn't a route report.
        }
    }
    internal fun close(cause: Int = DisconnectCause.LOCAL) {
        pendingSpeaker = null
        val old = entry ?: return; entry = null
        old.ready.cancel(); old.avatarJob?.cancel()
        try { system.cancel(ID) } catch (_: Exception) { callDiagnostic(context, CallEvent.PlatformFailure) }
        closing = scope.launch {
            try {
                val result = withTimeoutOrNull(3_000) { old.control?.disconnect(DisconnectCause(cause)) }
                if (result is CallControlResult.Error) diagnose("disconnect", old, code = result.errorCode)
                else diagnose("disconnect.complete", old)
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { diagnose("disconnect", old, error) }
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
        val builder = NotificationCompat.Builder(context, if (ringing) DISPLAY else ONGOING)
            .setSmallIcon(android.R.drawable.stat_sys_phone_call).setContentTitle(state.name.ifBlank { "Telegram voice call" })
            .setContentText(if (ringing) "Incoming voice call" else when (state.phase) {
                VoiceCallPhase.Active -> "Voice call in progress"; VoiceCallPhase.Reconnecting -> "Reconnecting…"; else -> "Connecting…" })
            .setStyle(style).setContentIntent(open).setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true).setOnlyAlertOnce(true).setSilent(!ringing).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
        val fullScreen = Build.VERSION.SDK_INT < 34 || system.canUseFullScreenIntent()
        if (ringing) {
            callAudioDiagnostic("CALL_SCREEN_PRESENTATION", "fullScreenAllowed=$fullScreen locked=${context.getSystemService(KeyguardManager::class.java).isKeyguardLocked} interactive=${context.getSystemService(android.os.PowerManager::class.java).isInteractive}")
            if (fullScreen) builder.setFullScreenIntent(open, true)
        }
        // CallSoundController owns incoming ringtone/vibration; notification playback is always silent.
        return builder.build()
    }
    internal fun foregroundStarted(attempt: Long) {
        val value = entry?.takeIf { it.key.attempt == attempt && current(it.key) } ?: return
        value.foreground = true
        value.ringing = calls.state.value.phase == VoiceCallPhase.Incoming
        if (value.ringing) callDiagnostic(context, CallEvent.IncomingPosted)
        value.operation = "callScope"
        diagnose("startForeground.complete", value)
        value.ready.complete(Unit)
    }
    internal fun foregroundFailed(attempt: Long, error: Throwable) {
        val value = entry?.takeIf { it.key.attempt == attempt && current(it.key) } ?: return
        diagnose("startForeground", value, error)
        value.ready.completeExceptionally(error)
        calls.end("Android could not start the call service.")
    }
    private fun post(value: Entry, state: VoiceCallState) {
        if (!current(value.key) || !value.foreground) return
        // Never cancel the foreground notification while changing incoming/ongoing style.
        val newlyRinging = !value.ringing && state.phase == VoiceCallPhase.Incoming
        try {
            system.notify(ID, notification(value, state))
            value.ringing = state.phase == VoiceCallPhase.Incoming
            if (newlyRinging) callDiagnostic(context, CallEvent.IncomingPosted)
        } catch (error: Exception) {
            diagnose("updateCallNotification", value, error)
            if (current(value.key)) calls.end("Android could not maintain the call notification.")
        }
    }
    internal fun silenceIncoming(key: PlatformCall) {
        val value = entry?.takeIf { it.key == key && current(key) } ?: return
        calls.sounds.stopIncomingCall()
        // Keep the visible notification in sync; it never owns ringtone audio.
        post(value, calls.state.value.copy(phase = VoiceCallPhase.Connecting))
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
