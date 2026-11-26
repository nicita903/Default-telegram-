package com.vector.messenger.calling

import android.content.Context
import android.hardware.*
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.util.Log

/** Service-owned OS proximity lock. Sensor callbacks are diagnostics, never a display overlay. */
internal class CallProximityController(private val context: Context) : SensorEventListener {
    private val power = context.getSystemService(PowerManager::class.java)
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val sensor = sensors?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private var lock: PowerManager.WakeLock? = null
    private var listening = false
    private var near: Boolean? = null
    private var state = VoiceCallState()
    private var enabled = false
    private var closed = false
    private var route = AudioDeviceInfo.TYPE_UNKNOWN
    private var established = false
    private var deviceListener: AudioManager.OnCommunicationDeviceChangedListener? = null
    val supported = supported(context)
    init {
        log("PROXIMITY_SUPPORTED=$supported sensorAvailable=${sensor != null}")
        if (Build.VERSION.SDK_INT >= 31) try {
            val listener = AudioManager.OnCommunicationDeviceChangedListener { evaluate() }
            audio.addOnCommunicationDeviceChangedListener(context.mainExecutor, listener)
            deviceListener = listener
        } catch (failure: RuntimeException) { log("AUDIO_ROUTE_LISTENER_FAILED=${failure.javaClass.simpleName}") }
    }
    fun update(state: VoiceCallState, enabled: Boolean) {
        this.state = state; this.enabled = enabled
        evaluate()
    }
    private fun evaluate() {
        if (closed) return
        val active = state.audioReady && state.connectedAt > 0 && state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting)
        if (active != established) { established = active; log("CALL_STATE=${if (active) "ESTABLISHED" else "INACTIVE"}") }
        val actual = if (Build.VERSION.SDK_INT >= 31) try { audio.communicationDevice?.type } catch (_: RuntimeException) { null } else null
        // A system-selected device wins; otherwise the current Telecom endpoint is authoritative.
        val output = actual ?: state.audioOutput
        if (route != output) { route = output; log("AUDIO_ROUTE=" + when (output) {
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "EARPIECE"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET -> "BLUETOOTH"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED"
            else -> "OTHER_OR_UNKNOWN"
        }) }
        if (supported && enabled && active && !state.video && output == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
            try {
                val current = lock ?: power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "Default:call-proximity").apply { setReferenceCounted(false) }.also { lock = it }
                if (!current.isHeld) { current.acquire(); log("PROXIMITY_LOCK_ACQUIRED=${current.isHeld}") }
                if (!listening && sensor != null) listening = sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (failure: RuntimeException) { log("PROXIMITY_FAILURE=${failure.javaClass.simpleName}"); release() }
        } else release()
    }
    fun release() {
        if (listening) { sensors.unregisterListener(this); listening = false; near = null }
        val old = lock ?: return
        try {
            // Route changes and hangup must restore the screen even while the sensor is covered.
            if (old.isHeld) old.release()
            log("PROXIMITY_LOCK_RELEASED=${!old.isHeld}")
            if (!old.isHeld) lock = null
        } catch (failure: RuntimeException) { log("PROXIMITY_RELEASE_FAILED=${failure.javaClass.simpleName}") }
    }
    fun close() {
        closed = true
        release()
        if (Build.VERSION.SDK_INT >= 31) deviceListener?.let { audio.removeOnCommunicationDeviceChangedListener(it) }
        deviceListener = null
    }
    override fun onSensorChanged(event: SensorEvent) {
        val value = event.values.firstOrNull()?.let { it < minOf(event.sensor.maximumRange, 5f) } ?: return
        if (near != value) { near = value; log("PROXIMITY_NEAR=$value") }
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    private fun log(event: String) { Log.d("DefaultProximity", "t=${android.os.SystemClock.elapsedRealtime()} $event") }
    companion object {
        // Android may expose platform-managed/virtual proximity without an app-visible sensor.
        fun supported(context: Context): Boolean = context.getSystemService(PowerManager::class.java)
            .isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
    }
}
