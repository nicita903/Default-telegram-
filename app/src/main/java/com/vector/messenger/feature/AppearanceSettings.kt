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

enum class InterfaceStyle { Console, AuroraGlass }

/** Non-sensitive UI preferences only. Never stores account information or search queries. */
data class AppearanceSettings(
    val modules: Boolean = true, val distance: Int = 28, val hold: Int = 180,
    val haptics: Boolean = true, val zoom: Int = 5, val speed: Int = 100,
    val pullSearch: Boolean = true, val pullDistance: Int = 72, val dismissSearch: Boolean = true,
    val fastSearch: Boolean = true, val sensitivity: Int = 100,
    val autoLock: Boolean = true, val lockVisible: Boolean = true, val elastic: Boolean = true,
    val compact: Boolean = true, val accent: Int = 0, val intensity: Int = 100,
    val reducedMotion: Boolean = false, val typeScale: Int = 100,
    val amoledBlack: Boolean = false, val glassBlur: Int = 2,
    val interfaceStyle: InterfaceStyle = InterfaceStyle.AuroraGlass,
)

/** Shared bounds for loading and live updates: malformed configuration cannot disable navigation. */
fun AppearanceSettings.normalized() = copy(
    distance = distance.coerceIn(20, 60), hold = hold.coerceIn(120, 400), zoom = zoom.coerceIn(0, 10),
    speed = speed.coerceIn(50, 150), pullDistance = pullDistance.coerceIn(48, 120),
    sensitivity = sensitivity.coerceIn(70, 140), accent = accent.coerceIn(0, 2),
    intensity = intensity.coerceIn(0, 100), typeScale = typeScale.coerceIn(85, 130),
    glassBlur = glassBlur.coerceIn(0, 3),
)

fun AppearanceSettings.respectingSystemMotion(animationsEnabled: Boolean) =
    copy(reducedMotion = reducedMotion || !animationsEnabled)

val LocalAppearance = staticCompositionLocalOf { AppearanceSettings() }

interface AppearancePreferences {
    val state: StateFlow<AppearanceSettings>
    fun update(change: (AppearanceSettings) -> AppearanceSettings)
}

@Singleton
class AppearanceStore @Inject constructor(@ApplicationContext context: Context) : AppearancePreferences {
    private val prefs = context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(AppearanceSettings(
        interfaceStyle = runCatching { InterfaceStyle.valueOf(prefs.getString("interfaceStyle", "AuroraGlass") ?: "AuroraGlass") }.getOrDefault(InterfaceStyle.AuroraGlass),
        modules = prefs.getBoolean("modules", true), distance = prefs.getInt("distance", 28),
        hold = prefs.getInt("hold", 180), haptics = prefs.getBoolean("haptics", true),
        zoom = prefs.getInt("zoom", 5), speed = prefs.getInt("speed", 100),
        pullSearch = prefs.getBoolean("pullSearch", true), pullDistance = prefs.getInt("pullDistance", 72),
        dismissSearch = prefs.getBoolean("dismissSearch", true),
        fastSearch = prefs.getBoolean("fastSearch", true), sensitivity = prefs.getInt("sensitivity", 100),
        autoLock = prefs.getBoolean("autoLock", true), lockVisible = prefs.getBoolean("lockVisible", true),
        elastic = prefs.getBoolean("elastic", true), compact = prefs.getBoolean("compact", true),
        accent = prefs.getInt("accent", 0), intensity = prefs.getInt("intensity", 100),
        reducedMotion = prefs.getBoolean("reducedMotion", false), typeScale = prefs.getInt("typeScale", 100),
        amoledBlack = prefs.getBoolean("amoledBlack", false), glassBlur = prefs.getInt("glassBlur", 2),
    ).normalized())
    override val state = mutable.asStateFlow()
    @Synchronized
    override fun update(change: (AppearanceSettings) -> AppearanceSettings) {
        val next = change(mutable.value).normalized()
        if (next == mutable.value) return
        mutable.value = next
        prefs.edit { putString("interfaceStyle", next.interfaceStyle.name).putBoolean("modules", next.modules).putInt("distance", next.distance).putInt("hold", next.hold)
            .putBoolean("haptics", next.haptics).putInt("zoom", next.zoom).putInt("speed", next.speed)
            .putBoolean("pullSearch", next.pullSearch).putInt("pullDistance", next.pullDistance).putBoolean("dismissSearch", next.dismissSearch)
            .putBoolean("fastSearch", next.fastSearch).putInt("sensitivity", next.sensitivity)
            .putBoolean("autoLock", next.autoLock).putBoolean("lockVisible", next.lockVisible).putBoolean("elastic", next.elastic)
            .putBoolean("compact", next.compact).putInt("accent", next.accent).putInt("intensity", next.intensity)
            .putBoolean("reducedMotion", next.reducedMotion).putInt("typeScale", next.typeScale)
            .putBoolean("amoledBlack", next.amoledBlack).putInt("glassBlur", next.glassBlur)
            // Removed controls: dropped on the next write so an old install cannot revive them.
            .remove("glassEffects").remove("glassOpacity")
            .remove("glassBorders").remove("reducedTransparency") }
    }
}
