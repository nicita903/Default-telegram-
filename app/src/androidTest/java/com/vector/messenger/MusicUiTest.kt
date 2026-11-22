package com.vector.messenger

import android.content.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.AppearanceSettings
import com.vector.messenger.feature.LocalAppearance
import com.vector.messenger.feature.audio.SharedMediaPlayer
import com.vector.messenger.feature.music.*
import com.vector.messenger.feature.search.MusicLibraryRepository
import java.util.UUID
import kotlinx.coroutines.*
import org.junit.*

class MusicUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun createRenameAndDeletePlaylistChangesRealIsolatedPreferences() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = "music-ui-${UUID.randomUUID()}"
        val first = "Fixture ${UUID.randomUUID()}"
        val renamed = "Renamed ${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) { override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefs, mode) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = ViewModelStore()
        lateinit var vm: MusicViewModel
        lateinit var engine: SharedMediaPlayer
        compose.runOnUiThread {
            engine = SharedMediaPlayer(context, scope)
            val repo = MusicLibraryRepository(isolated, scope)
            vm = MusicViewModel(repo, MusicCoordinator(context, engine, repo, scope)); store.put("music", vm)
        }
        try {
            compose.setContent { CompositionLocalProvider(LocalAppearance provides AppearanceSettings()) {
                DefaultTheme { CompositionLocalProvider(LocalMusicViewModel provides vm) { MusicLibrarySection("Playlists") } }
            } }
            compose.onNodeWithText("Create playlist").performClick()
            compose.onNodeWithContentDescription("Playlist name").performTextInput(first)
            compose.onNodeWithText("Save playlist").performClick()
            compose.waitUntil(5000) { vm.library.value.playlists.any { it.name == first } }
            compose.onNodeWithText(first).performClick()
            compose.onNodeWithText("Rename").performClick()
            compose.onNodeWithContentDescription("Playlist name").performTextReplacement(renamed)
            compose.onNodeWithText("Save playlist").performClick()
            compose.waitUntil(5000) { vm.library.value.playlists.any { it.name == renamed } }
            compose.onNodeWithText("Delete").performClick()
            compose.onNodeWithText("Delete playlist").performClick()
            compose.waitUntil(5000) { vm.library.value.playlists.none { it.name == renamed } }
            Assert.assertTrue(MusicPreferences(isolated).load().playlists.isEmpty())
            compose.onNodeWithTag("music_section:Playlists").assertIsDisplayed()
        } finally {
            compose.runOnUiThread { store.clear(); engine.reset(); scope.cancel() }
            context.deleteSharedPreferences(prefs)
        }
    }
}

