package com.vector.messenger

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DefaultApplication : Application() {
    @javax.inject.Inject lateinit var notifications: com.vector.messenger.notifications.TelegramNotifications
    @javax.inject.Inject lateinit var authentication: com.vector.core.auth.AuthenticationRepository
    @javax.inject.Inject lateinit var calls: com.vector.messenger.calling.TelegramVoiceCalls
    override fun onCreate() {
        super.onCreate()
        com.vector.messenger.notifications.PushDiagnostics.install(this)
        com.vector.messenger.telegram.SessionDiagnostics.install(this)
        // Restoration must never depend on notification channels, permission, or Firebase.
        authentication.start()
        calls.start()
        try { notifications.start() }
        catch (_: Exception) { android.util.Log.w("DefaultNotifications", "Notification startup deferred; Telegram restoration continues") }
        catch (_: LinkageError) { android.util.Log.w("DefaultNotifications", "Notification dependency unavailable; Telegram restoration continues") }
    }
}
