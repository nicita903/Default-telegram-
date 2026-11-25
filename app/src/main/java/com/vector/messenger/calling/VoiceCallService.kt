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

/** Exists only for an accepted Telecom session; ringing never captures microphone audio. */
@AndroidEntryPoint
class VoiceCallService : Service() {
    @Inject lateinit var calls: TelegramVoiceCalls
    private var attempt = -1L
    override fun onCreate() {
        super.onCreate()
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
            calls.platform.foregroundStarted(attempt)
        } catch (error: RuntimeException) {
            calls.platform.foregroundFailed(attempt, error)
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) { calls.end(); super.onTaskRemoved(rootIntent) }
    override fun onDestroy() {
        calls.serviceStopped(attempt)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null
}
