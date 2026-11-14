package com.vector.messenger

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.messenger.feature.*
import org.junit.Assert.*
import org.junit.Test

class AppearancePersistenceTest {
    @Test fun everyPreferenceSurvivesReopeningAndUpdatesImmediately() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance_persistence_test"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        target.deleteSharedPreferences(name)
        try {
            val store = AppearanceStore(context)
            assertEquals(AppearanceSettings(), store.state.value)
            val expected = AppearanceSettings(modules = false, distance = 48, hold = 320, haptics = false,
                zoom = 8, speed = 125, pullSearch = false, pullDistance = 96, dismissSearch = false,
                fastSearch = false, sensitivity = 120, autoLock = false, lockVisible = false, elastic = false,
                compact = false, accent = 2, intensity = 75, reducedMotion = true, typeScale = 115)
            store.update { expected }; store.theme("Light")
            assertEquals(expected, store.state.value)
            // Barrier waits for queued apply() writes; subsequent construction uses the persisted file.
            assertTrue(context.getSharedPreferences("appearance_v2", Context.MODE_PRIVATE).edit().commit())
            val persisted = java.io.File(target.applicationInfo.dataDir, "shared_prefs/$name.xml").readText()
            assertTrue(persisted.contains("name=\"dismissSearch\" value=\"false\""))
            assertTrue(persisted.contains("name=\"typeScale\" value=\"115\""))
            val restored = AppearanceStore(context)
            assertEquals(expected, restored.state.value)
            assertEquals("Light", restored.theme.value)
            restored.theme("invalid"); assertEquals("Light", restored.theme.value)
            restored.update { it.copy(speed = 0, distance = 999, accent = -1) }
            assertEquals(50, restored.state.value.speed)
            assertEquals(60, AppearanceStore(context).state.value.distance)
            assertEquals(0, AppearanceStore(context).state.value.accent)
        } finally { target.deleteSharedPreferences(name) }
    }
    @Test fun olderPreferenceFilesReceiveSafeDismissalDefault() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "appearance_migration_test"
        val context = object : ContextWrapper(target) {
            override fun getSharedPreferences(ignored: String, mode: Int) = target.getSharedPreferences(name, mode)
        }
        try {
            target.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().putInt("distance", 28).commit()
            assertTrue(AppearanceStore(context).state.value.dismissSearch)
        } finally { target.deleteSharedPreferences(name) }
    }
}
