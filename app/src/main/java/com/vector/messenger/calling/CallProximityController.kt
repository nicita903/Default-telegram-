package com.vector.messenger.calling

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioDeviceInfo
import android.os.PowerManager

/** Android monitors proximity itself. No sensor polling, screen overlay, or brightness changes. */
internal class CallProximityController(context: Context) {
    private val power = context.getSystemService(PowerManager::class.java)
    private var lock: PowerManager.WakeLock? = null
    val supported = supported(context)
    fun update(state: VoiceCallState, enabled: Boolean) {
        val active = state.audioReady && state.phase in listOf(VoiceCallPhase.Active, VoiceCallPhase.Reconnecting)
        val earpiece = state.audioOutput == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        if (supported && enabled && active && state.audioReady && !state.video && earpiece) {
            try {
                val current = lock ?: power.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK,
                    "Default:call-proximity").apply { setReferenceCounted(false) }.also { lock = it }
                if (!current.isHeld) current.acquire()
            } catch (_: RuntimeException) { release(false) }
        } else release(waitForFar = !active && !state.video)
    }
    fun release(waitForFar: Boolean = true) {
        val old = lock ?: return
        lock = null
        try {
            if (old.isHeld) old.release(if (waitForFar) PowerManager.RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY else 0)
        } catch (_: RuntimeException) { /* Idempotent when Android already removed the lock. */ }
    }
    companion object {
        fun supported(context: Context): Boolean =
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null &&
                context.getSystemService(PowerManager::class.java).isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)
    }
}
