package com.vector.messenger.notifications

/** Fixed stages only; no token, payload, sender, message, account or native error text. */
internal object PushDiagnostics {
    fun event(stage: String, correlation: String = "session") {
        android.util.Log.i("DefaultPush", "t=${android.os.SystemClock.elapsedRealtime()} ref=${correlation.take(12)} $stage")
    }
}
