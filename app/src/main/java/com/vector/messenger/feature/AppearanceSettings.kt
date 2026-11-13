package com.vector.messenger.feature

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.StateFlow
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import androidx.core.content.edit

/** Non-sensitive UI preferences only. Never stores account information or search queries. */
data class AppearanceSettings(
    val modules: Boolean = true, val distance: Int = 28, val hold: Int = 180,
    val haptics: Boolean = true, val zoom: Int = 5, val speed: Int = 100,
    val pullSearch: Boolean = true, val pullDistance: Int = 72,
    val fastSearch: Boolean = true, val sensitivity: Int = 100,
    val autoLock: Boolean = true, val lockVisible: Boolean = true, val elastic: Boolean = true,
    val compact: Boolean = true, val accent: Int = 0, val intensity: Int = 100,
    val reducedMotion: Boolean = false, val typeScale: Int = 100,
)

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

interface AppearancePreferences {
    val state: StateFlow<AppearanceSettings>
    val theme: StateFlow<String>
    fun theme(value: String)
    fun update(change: (AppearanceSettings) -> AppearanceSettings)
}

@Singleton
class AppearanceStore @Inject constructor(@ApplicationContext context: Context) : AppearancePreferences {
    private val prefs = context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppearanceSettings(
        modules = prefs.getBoolean("modules", true), distance = prefs.getInt("distance", 28).coerceIn(20, 60),
        hold = prefs.getInt("hold", 180).coerceIn(120, 400), haptics = prefs.getBoolean("haptics", true),
        zoom = prefs.getInt("zoom", 5).coerceIn(0, 10), speed = prefs.getInt("speed", 100).coerceIn(50, 150),
        pullSearch = prefs.getBoolean("pullSearch", true), pullDistance = prefs.getInt("pullDistance", 72).coerceIn(48, 120),
        fastSearch = prefs.getBoolean("fastSearch", true), sensitivity = prefs.getInt("sensitivity", 100).coerceIn(70, 140),
        autoLock = prefs.getBoolean("autoLock", true), lockVisible = prefs.getBoolean("lockVisible", true),
        elastic = prefs.getBoolean("elastic", true), compact = prefs.getBoolean("compact", true),
        accent = prefs.getInt("accent", 0).coerceIn(0, 2), intensity = prefs.getInt("intensity", 100).coerceIn(0, 100),
        reducedMotion = prefs.getBoolean("reducedMotion", false), typeScale = prefs.getInt("typeScale", 100).coerceIn(85, 130),
    ))
    override val state = mutable.asStateFlow()
    private val themeState = MutableStateFlow(prefs.getString("theme", "System")?.takeIf { it in listOf("System", "Dark", "Light") } ?: "System")
    override val theme = themeState.asStateFlow()
    override fun theme(value: String) { themeState.value = value; prefs.edit { putString("theme", value) } }
    override fun update(change: (AppearanceSettings) -> AppearanceSettings) {
        val next = change(mutable.value)
        mutable.value = next
        prefs.edit { putBoolean("modules", next.modules).putInt("distance", next.distance).putInt("hold", next.hold)
            .putBoolean("haptics", next.haptics).putInt("zoom", next.zoom).putInt("speed", next.speed)
            .putBoolean("pullSearch", next.pullSearch).putInt("pullDistance", next.pullDistance)
            .putBoolean("fastSearch", next.fastSearch).putInt("sensitivity", next.sensitivity)
            .putBoolean("autoLock", next.autoLock).putBoolean("lockVisible", next.lockVisible).putBoolean("elastic", next.elastic)
            .putBoolean("compact", next.compact).putInt("accent", next.accent).putInt("intensity", next.intensity)
            .putBoolean("reducedMotion", next.reducedMotion).putInt("typeScale", next.typeScale) }
    }
}
