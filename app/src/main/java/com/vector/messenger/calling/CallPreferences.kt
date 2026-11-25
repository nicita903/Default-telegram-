package com.vector.messenger.calling

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class CallSettings(val effects: Boolean = true, val proximity: Boolean = true)

@Singleton
class CallPreferences @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("calling", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(CallSettings(prefs.getBoolean("effects", true), prefs.getBoolean("proximity", true)))
    val state = mutable.asStateFlow()
    fun update(change: (CallSettings) -> CallSettings) {
        val next = change(mutable.value)
        prefs.edit().putBoolean("effects", next.effects).putBoolean("proximity", next.proximity).apply()
        mutable.value = next
    }
}
