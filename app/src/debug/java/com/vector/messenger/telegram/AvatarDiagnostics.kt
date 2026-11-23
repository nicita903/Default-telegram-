package com.vector.messenger.telegram

import android.util.Log

/** Stage names only; never IDs, paths, names or server payloads. Off by default.
 * Enable locally with `adb shell setprop log.tag.DefaultAvatar DEBUG`.
 */
internal object AvatarDiagnostics {
    fun record(stage: AvatarStage) {
        if (Log.isLoggable("DefaultAvatar", Log.DEBUG)) Log.d("DefaultAvatar", stage.name)
    }
}
