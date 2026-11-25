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

/** Exists only for a user-started/answered call; never captures or polls on its own. */
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
        val open = PendingIntent.getActivity(this, 8401, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val endIntent = Intent(this, VoiceCallService::class.java).setAction("end")
            .setData(android.net.Uri.parse("default://call/end/$token")).putExtra("attempt", token)
        val end = PendingIntent.getService(this, 8402, endIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notice = NotificationCompat.Builder(this, "default_voice_calls").setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setContentTitle("Default voice call").setContentText("Tap to return to the call").setContentIntent(open)
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_CALL).setOnlyAlertOnce(true)
            .addAction(0, "End call", end).build()
        try { ServiceCompat.startForeground(this, 8400, notice, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        catch (_: RuntimeException) { calls.serviceStopped(attempt); stopSelf(startId) }
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
