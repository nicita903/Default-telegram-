package com.vector.messenger.calling

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.vector.messenger.MainActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect

/** Exists only for an accepted Telecom session; ringing never captures microphone audio. */
@AndroidEntryPoint
class VoiceCallService : Service() {
    @Inject lateinit var calls: TelegramVoiceCalls
    private var attempt = -1L
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var feedback: Job? = null
    private lateinit var proximity: CallProximityController
    override fun onCreate() {
        super.onCreate()
        proximity = CallProximityController(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("default_voice_calls", "Voice calls", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "end") {
            calls.endServiceAttempt(intent.getLongExtra("attempt", -1L))
            if (!calls.ownsServiceAttempt(attempt)) stopSelf(startId)
            return START_NOT_STICKY
        }
        val token = intent?.getLongExtra("attempt", -1L) ?: -1L
        if (token < 0 || !calls.ownsServiceAttempt(token)) { stopSelf(startId); return START_NOT_STICKY }
        attempt = token
        try {
            val notice = calls.platform.foregroundNotification()
            if (notice == null) { stopSelf(startId); return START_NOT_STICKY }
            ServiceCompat.startForeground(this, TelegramCallPlatform.ID, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL)
            calls.sounds.begin(token)
            feedback?.cancel()
            feedback = serviceScope.launch {
                combine(calls.state, calls.settings.state) { state, settings -> state to settings }.collect { (state, settings) ->
                    val current = if (calls.ownsServiceAttempt(token)) state else state.copy(audioReady = false)
                    calls.sounds.update(token, current, settings.effects)
                    proximity.update(current, settings.proximity)
                }
            }
            calls.platform.foregroundStarted(attempt)
        } catch (error: RuntimeException) {
            calls.platform.foregroundFailed(attempt, error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { calls.end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        serviceScope.cancel()
        proximity.release()
        calls.serviceStopped(attempt)
        calls.sounds.serviceStopped(attempt)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
