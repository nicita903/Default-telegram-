package com.vector.messenger.sound

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SoundSettings(val messages: Boolean = true, val send: Boolean = true, val receive: Boolean = true,
    val errors: Boolean = false, val interfaceSounds: Boolean = false, val volume: Int = 45)

@Singleton
class SoundPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(SoundSettings(prefs.getBoolean("sounds.messages", true), prefs.getBoolean("sounds.send", true),
        prefs.getBoolean("sounds.receive", true), prefs.getBoolean("sounds.errors", false), prefs.getBoolean("sounds.interface", false), prefs.getInt("sounds.volume", 45).coerceIn(0, 100)))
    val state = mutable.asStateFlow()
    fun update(change: (SoundSettings) -> SoundSettings) {
        val next = change(mutable.value).let { it.copy(volume = it.volume.coerceIn(0, 100)) }
        if (next == mutable.value) return
        prefs.edit().putBoolean("sounds.messages", next.messages).putBoolean("sounds.send", next.send).putBoolean("sounds.receive", next.receive)
            .putBoolean("sounds.errors", next.errors).putBoolean("sounds.interface", next.interfaceSounds).putInt("sounds.volume", next.volume).apply()
        mutable.value = next
    }
}
