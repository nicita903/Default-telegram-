package com.vector.messenger.calling

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.core.content.ContextCompat
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.feature.audio.SharedMediaPlayer
import com.vector.messenger.telegram.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject

val LocalVoiceCalls = androidx.compose.runtime.staticCompositionLocalOf<TelegramVoiceCalls?> { null }
enum class CallSecurityStage { Unavailable, Negotiated, MediaInitialized, MediaEstablished }
data class CallSecurityUiState(val callId: Int, val verificationEmojis: List<String>, val stage: CallSecurityStage) {
    val available get() = verificationEmojis.size == 4
    override fun toString() = "CallSecurityUiState([redacted])"
}
enum class VoiceCallPhase { Idle, Requesting, Incoming, Ringing, Connecting, Active, Reconnecting, Ending, Ended, Failed }
data class VoiceCallState(val id: Int = 0, val user: Long = 0, val name: String = "", val phase: VoiceCallPhase = VoiceCallPhase.Idle,
    val muted: Boolean = false, val speaker: Boolean = false, val connectedAt: Long = 0, val error: String? = null, val outgoing: Boolean = false, val video: Boolean = false, val security: CallSecurityUiState? = null) {
    override fun toString() = "VoiceCallState([redacted])"
}

@Singleton
class TelegramVoiceCalls @Inject constructor(@ApplicationContext private val context: Context, private val manager: TdlibClientManager,
    private val profile: ProfileRepository, private val transport: TdlibAvatarClient, private val player: SharedMediaPlayer,
    private val scope: CoroutineScope, val privacy: CallPrivacy) {
    private val mutable = MutableStateFlow(VoiceCallState())
    val state = mutable.asStateFlow()
    private var bridge: TgCallsBridge? = null
    private var handle = 0L
    private var owner = 0L
    private var epoch = 0L
    private var started = false
    private var ending = false
    private var attempt = 0L
    private var audio: AudioManager = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var previousMode = AudioManager.MODE_NORMAL
    private var previousSpeaker = false
    private var priorDevice: AudioDeviceInfo? = null
    private val buffered = ArrayDeque<ByteArray>()
    private var requestJob: Job? = null
    private var mediaJob: Job? = null
    fun start() {
        if (started) return; started = true
        scope.launch { profile.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } }.distinctUntilChanged().collect { account ->
            if (owner != account) { epoch++; requestJob?.cancel(); release(); mutable.value = VoiceCallState(); owner = account ?: 0 }
        } }
        scope.launch { profile.state.map { it.profile?.id?.takeIf { _ -> it.authenticated } }.distinctUntilChanged().collectLatest { account ->
            if (account == null) return@collectLatest
            if (owner != account) { epoch++; release(); mutable.value = VoiceCallState(); owner = account }
            manager.callUpdates.collect { update ->
            if (owner == 0L || owner != account) return@collect
            try {
                when (update.optString("@type")) {
                    "updateCall" -> reduce(update.getJSONObject("call"))
                    "updateNewCallSignalingData" -> if (update.optInt("call_id") == state.value.id) {
                        val bytes = Base64.decode(update.optString("data"), Base64.DEFAULT)
                        if (!ending && bytes.size <= 1024 * 1024 && state.value.phase !in listOf(VoiceCallPhase.Ended, VoiceCallPhase.Failed)) {
                            if (handle != 0L) { try { bridge?.signal(handle, bytes) } finally { bytes.fill(0) } }
                            else if (buffered.size < 32) buffered.addLast(bytes) else bytes.fill(0)
                        } else bytes.fill(0)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (state.value.id != 0) end("Unable to establish Telegram call.") }
        } } }
    }
    private suspend fun call(value: JSONObject, account: Long = owner): JSONObject = transport.call(value, account)
    private fun engine(): TgCallsBridge {
        bridge?.let { return it }
        val created = TgCallsBridge(context); bridge = created
        scope.launch { for (event in created.events) when (event) {
            is NativeCallEvent.State -> if (event.handle == handle && !ending) when (event.value) {
                2 -> {
                    if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) android.util.Log.i("DefaultVoiceCall", "Native media established")
                    mutable.update { it.copy(phase = VoiceCallPhase.Active, security = it.security?.copy(stage = CallSecurityStage.MediaEstablished), connectedAt = it.connectedAt.takeIf { value -> value > 0 } ?: SystemClock.elapsedRealtime()) }
                }
                3 -> end("Voice transport failed.")
                4 -> mutable.update { it.copy(phase = VoiceCallPhase.Reconnecting) }
            }
            is NativeCallEvent.Signal -> { try { if (event.handle == handle && !ending) {
                val id = state.value.id; val account = owner
                try { call(request("sendCallSignalingData", "call_id" to id, "data" to Base64.encodeToString(event.bytes, Base64.NO_WRAP)), account) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { if (owner == account && state.value.id == id && event.handle == handle) end("Telegram signaling failed.") }
                } } finally { event.bytes.fill(0) } }
        } }
        return created
    }
    private fun protocol(): JSONObject {
        val supported = engine().capabilities
        return request("callProtocol", "udp_p2p" to true, "udp_reflector" to true,
            "min_layer" to supported.getInt("layer"), "max_layer" to supported.getInt("layer"), "library_versions" to supported.getJSONArray("versions"))
    }
    fun outgoing(user: Long) {
        start()
        if (owner == 0L || user <= 0 || state.value.phase !in listOf(VoiceCallPhase.Idle, VoiceCallPhase.Ended, VoiceCallPhase.Failed)) return
        ending = false; val attemptId = ++attempt
        mutable.value = VoiceCallState(user = user, phase = VoiceCallPhase.Requesting, outgoing = true)
        val token = epoch; val account = owner
        requestJob = scope.launch {
            try {
                val nativeProtocol = protocol() // Fail before CreateCall if the media library cannot load.
                checkPermission(); acquireAudio()
                val full = call(request("getUserFullInfo", "user_id" to user), account)
                check(full.optBoolean("can_be_called")) { "Contact does not permit calls" }
                val identity = transport.user(call(request("getUser", "user_id" to user), account))
                if (token != epoch || attemptId != attempt) return@launch
                mutable.update { it.copy(name = identity.name) }
                val result = call(request("createCall", "user_id" to user, "protocol" to nativeProtocol, "is_video" to false), account)
                if (token != epoch || attemptId != attempt) {
                    runCatching { call(request("discardCall", "call_id" to result.getInt("id"), "is_disconnected" to false,
                        "invite_link" to "", "duration" to 0, "is_video" to false, "connection_id" to 0), account) }
                    return@launch
                }
                mutable.update { it.copy(id = result.getInt("id")) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: LinkageError) { if (token == epoch && attemptId == attempt) fail("Native voice engine is not available for this build.") }
            catch (_: Exception) { if (token == epoch && attemptId == attempt) { if (state.value.id != 0) end("Unable to start this call.") else fail("Unable to start this call. Check permission, connection and contact call privacy.") } }
        }
    }
    fun answer() {
        if (state.value.phase != VoiceCallPhase.Incoming) return
        val id = state.value.id; val account = owner; val token = epoch
        mutable.update { it.copy(phase = VoiceCallPhase.Connecting) }
        scope.launch {
            try { val nativeProtocol = protocol(); checkPermission(); acquireAudio(); call(request("acceptCall", "call_id" to id, "protocol" to nativeProtocol), account) }
            catch (_: LinkageError) { if (epoch == token && state.value.id == id) end("Native voice engine unavailable.") }
            catch (_: Exception) { if (epoch == token && state.value.id == id) end("Unable to answer. Check microphone permission and connection.") }
        }
    }
    private suspend fun reduce(value: JSONObject) {
        val id = value.getInt("id"); val user = value.getLong("user_id"); val outgoing = value.optBoolean("is_outgoing")
        if (state.value.id != id) {
            if (state.value.phase == VoiceCallPhase.Requesting && outgoing && state.value.user == user) mutable.update { it.copy(id = id) }
            else if (!outgoing && value.optJSONObject("state")?.optString("@type") == "callStatePending" && state.value.phase in listOf(VoiceCallPhase.Idle, VoiceCallPhase.Ended, VoiceCallPhase.Failed)) {
                ending = false
                mutable.value = VoiceCallState(id, user, phase = VoiceCallPhase.Incoming, video = value.optBoolean("is_video"),
                    error = if (value.optBoolean("is_video")) "Video calling is not supported. You can decline this call." else null)
                val identity = transport.user(call(request("getUser", "user_id" to user)))
                if (state.value.id == id) mutable.update { it.copy(name = identity.name) }
            } else return
        }
        val remote = value.getJSONObject("state")
        when (remote.optString("@type")) {
            "callStatePending" -> if (outgoing && !ending) mutable.update { it.copy(phase = if (remote.optBoolean("is_received")) VoiceCallPhase.Ringing else VoiceCallPhase.Requesting) }
            "callStateExchangingKeys" -> if (!ending) mutable.update { it.copy(phase = VoiceCallPhase.Connecting) }
            "callStateReady" -> if (!ending && handle == 0L && mediaJob?.isActive != true) {
                if (value.optBoolean("is_video")) { end("Video calls are not supported in this phase."); return }
                mutable.update { it.copy(phase = VoiceCallPhase.Connecting) }
                val token = epoch
                mediaJob = scope.launch {
                    try { startMedia(remote, outgoing) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { if (epoch == token && state.value.id == id && !ending) end("Unable to initialize encrypted media.") }
                }
            }
            "callStateDiscarded" -> { ending = true; attempt++; release(); mutable.update { it.copy(phase = VoiceCallPhase.Ended) } }
            "callStateError" -> { ending = true; attempt++; release(); mutable.update { it.copy(phase = VoiceCallPhase.Failed, error = "Telegram ended the call with an error.") } }
            "callStateHangingUp" -> { ending = true; attempt++; release(); mutable.update { it.copy(phase = VoiceCallPhase.Ending) } }
        }
    }
    private suspend fun startMedia(ready: JSONObject, outgoing: Boolean) {
        val callId = state.value.id; val account = owner; val token = epoch; val attemptId = attempt
        fun current() = owner == account && epoch == token && attempt == attemptId && state.value.id == callId && !ending
        check(callId > 0 && account > 0)

        val engine = engine(); val versions = engine.capabilities.getJSONArray("versions")
        val remote = ready.getJSONObject("protocol"); val available = remote.getJSONArray("library_versions")
        val version = (0 until available.length()).map { available.getString(it) }.firstOrNull { candidate ->
            (0 until versions.length()).any { versions.getString(it) == candidate }
        } ?: error("No common native engine version")
        val key = Base64.decode(ready.getString("encryption_key"), Base64.DEFAULT)
        try {
            check(key.size == 256)
            val emojis = ready.optJSONArray("emojis")?.let { values ->
                if (values.length() != 4) emptyList() else (0 until 4).map { values.optString(it) }
                    .takeIf { list -> list.all { it.isNotBlank() && it.length <= 32 && it.none(Char::isISOControl) } }.orEmpty()
            }.orEmpty()
            if (!current()) return
            mutable.update { it.copy(security = CallSecurityUiState(callId, emojis, CallSecurityStage.Negotiated)) }
            check(remote.getInt("min_layer") <= engine.capabilities.getInt("layer") &&
                remote.getInt("max_layer") >= engine.capabilities.getInt("layer"))
            val servers = JSONArray()
            ready.getJSONArray("servers").objects().forEach { server ->
                val type = server.getJSONObject("type"); val reflector = type.optString("@type") == "callServerTypeTelegramReflector"
                val password = if (reflector) Base64.decode(type.getString("peer_tag"), Base64.DEFAULT).joinToString("") { "%02x".format(it.toInt() and 255) } else type.optString("password")
                servers.put(JSONObject().put("id", server.getLong("id").toString()).put("host", server.optString("ip_address").ifBlank { server.optString("ipv6_address") })
                    .put("port", server.getInt("port")).put("reflector", reflector).put("tcp", type.optBoolean("is_tcp"))
                    .put("turn", reflector || type.optBoolean("supports_turn")).put("username", if (reflector) "reflector" else type.optString("username")).put("password", password))
            }
            val connect = call(request("getOption", "name" to "call_connect_timeout_ms"), account).optLong("value", 30000) / 1000.0
            val packet = call(request("getOption", "name" to "call_packet_timeout_ms"), account).optLong("value", 10000) / 1000.0
            if (!current()) return
            handle = engine.create(version, key, JSONObject().put("outgoing", outgoing).put("servers", servers)
                .put("p2p", ready.optBoolean("allow_p2p") && remote.optBoolean("udp_p2p"))
                .put("layer", minOf(remote.getInt("max_layer"), engine.capabilities.getInt("layer"))).put("custom", ready.optString("custom_parameters"))
                .put("connectTimeout", connect).put("packetTimeout", packet))
            mutable.update { it.copy(security = it.security?.copy(stage = CallSecurityStage.MediaInitialized)) }
            while (buffered.isNotEmpty()) buffered.removeFirst().let { try { engine.signal(handle, it) } finally { it.fill(0) } }
        } finally { key.fill(0) }
    }
    fun mute() { if (handle == 0L || ending) return; val next = !state.value.muted; bridge?.mute(handle, next); mutable.update { it.copy(muted = next) } }
    fun speaker() {
        val next = !state.value.speaker
        if (Build.VERSION.SDK_INT >= 31) {
            val type = if (next) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
            val device = audio.availableCommunicationDevices.firstOrNull { it.type == type } ?: return
            if (!audio.setCommunicationDevice(device)) return
        } else { @Suppress("DEPRECATION") audio.isSpeakerphoneOn = next }
        mutable.update { it.copy(speaker = next) }
    }
    fun bluetooth() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val device = audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?: return
                if (audio.setCommunicationDevice(device)) mutable.update { it.copy(speaker = false) }
            } else { @Suppress("DEPRECATION") audio.startBluetoothSco(); @Suppress("DEPRECATION") audio.isBluetoothScoOn = true }
        } catch (_: SecurityException) { mutable.update { it.copy(error = "Allow nearby-device access to route audio to a Bluetooth headset.") } }
    }
    fun end(error: String? = null) {
        if (ending || state.value.phase in listOf(VoiceCallPhase.Idle, VoiceCallPhase.Ended)) return
        ending = true; attempt++
        val record = state.value; val account = owner; val token = epoch
        release()
        mutable.update { it.copy(phase = VoiceCallPhase.Ending) }
        scope.launch {
            try { if (record.id != 0 && account != 0L) call(request("discardCall", "call_id" to record.id, "is_disconnected" to (error != null), "invite_link" to "",
                "duration" to if (record.connectedAt > 0) ((SystemClock.elapsedRealtime() - record.connectedAt) / 1000).toInt() else 0, "is_video" to record.video, "connection_id" to 0), account) }
            catch (_: Exception) { /* Local capture must stop even if Telegram is temporarily unreachable. */ }
            finally { if (epoch == token && state.value.id == record.id) mutable.update { it.copy(phase = if (error == null) VoiceCallPhase.Ended else VoiceCallPhase.Failed, error = error) } }
        }
    }
    internal fun serviceStopped(serviceAttempt: Long) { if (serviceAttempt == attempt && focus != null) end("Call service stopped.") }
    fun dismiss() { if (state.value.phase in listOf(VoiceCallPhase.Ended, VoiceCallPhase.Failed)) mutable.value = VoiceCallState() }
    private fun checkPermission() { check(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    private fun acquireAudio() {
        checkPermission(); player.stop()
        previousMode = audio.mode; @Suppress("DEPRECATION") val old = audio.isSpeakerphoneOn; previousSpeaker = old
        if (Build.VERSION.SDK_INT >= 31) priorDevice = audio.communicationDevice
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { change -> if (change == AudioManager.AUDIOFOCUS_LOSS) end("Call interrupted by another audio session.") }.build()
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        focus = request
        ContextCompat.startForegroundService(context, android.content.Intent(context, VoiceCallService::class.java).putExtra("attempt", attempt))
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) audio.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }?.let { audio.setCommunicationDevice(it) }
        else { @Suppress("DEPRECATION") audio.isSpeakerphoneOn = false }
        ending = false
    }
    private fun release() {
        mediaJob?.cancel(); mediaJob = null
        mutable.update { it.copy(security = null) }
        context.stopService(android.content.Intent(context, VoiceCallService::class.java))
        val old = handle; handle = 0; if (old != 0L) bridge?.stop(old)
        buffered.forEach { it.fill(0) }; buffered.clear()
        if (focus != null) {
            if (Build.VERSION.SDK_INT >= 31) { audio.clearCommunicationDevice(); priorDevice?.let { audio.setCommunicationDevice(it) } }
            else { @Suppress("DEPRECATION") audio.stopBluetoothSco(); @Suppress("DEPRECATION") audio.isBluetoothScoOn = false; @Suppress("DEPRECATION") audio.isSpeakerphoneOn = previousSpeaker }
            audio.mode = previousMode; focus?.let(audio::abandonAudioFocusRequest); focus = null
        }
    }
    fun permissionDenied() { if (state.value.phase !in listOf(VoiceCallPhase.Idle, VoiceCallPhase.Ended, VoiceCallPhase.Failed)) return; fail("Microphone permission is required to make or answer a voice call.") }
    private fun fail(reason: String) { ending = true; attempt++; release(); mutable.update { it.copy(phase = VoiceCallPhase.Failed, error = reason) } }
}
