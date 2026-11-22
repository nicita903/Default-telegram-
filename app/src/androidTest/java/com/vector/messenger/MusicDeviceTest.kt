package com.vector.messenger

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.media3.common.Player
import androidx.media3.session.*
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.music.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.AppearanceSettings
import com.vector.messenger.feature.LocalAppearance
import com.vector.core.media.*
import com.vector.messenger.feature.audio.*
import com.vector.messenger.feature.music.*
import com.vector.messenger.feature.search.MusicLibraryRepository
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.TimeUnit

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MusicDeviceTest {
    @get:Rule val compose = createComposeRule()
    private fun wave(): ByteArray {
        val samples = 44100 * 30
        return ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray())
            putInt(16); putShort(1); putShort(1); putInt(44100); putInt(88200); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            // Zero-initialized PCM: a real playable, silent fixture rather than personal media.
        }.array()
    }
    @Test fun realMediaStoreScanSessionControlsBackgroundAndVoiceOwnership() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val permission = MusicLibraryRepository.permission
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        val presentation = mutableStateOf(AppearanceSettings())
        compose.setContent { CompositionLocalProvider(LocalAppearance provides presentation.value) {
            DefaultTheme { Text("Default music fixture") }
        } }
        val coordinator = EntryPointAccessors.fromApplication(context, MusicPlaybackEntryPoint::class.java).coordinator()
        val repository = coordinator.library
        val engine = coordinator.engine
        val saved = withTimeout(5000) { repository.settingsSnapshotForTest() }
        var controller: MediaController? = null
        val uris = mutableListOf<android.net.Uri>()
        val subscription = launch(Dispatchers.Main) { repository.library.collect {} }
        try {
            repeat(2) { index ->
                val values = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, "default-fixture-${UUID.randomUUID()}.wav")
                    put(MediaStore.Audio.Media.TITLE, "Default fixture $index")
                    put(MediaStore.Audio.Media.ARTIST, "Synthetic fixture")
                    put(MediaStore.Audio.Media.ALBUM, "Default fixtures")
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                    put(MediaStore.Audio.Media.IS_MUSIC, 1)
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/DefaultFixtures/")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
                val uri = requireNotNull(context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)); uris += uri
                context.contentResolver.openOutputStream(uri)!!.use { it.write(wave()) }
                context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            }
            repository.refresh()
            val library = withTimeout(15000) { repository.library.first { lib -> uris.all { uri -> lib.songs.any { it.id == android.content.ContentUris.parseId(uri) } } } }
            val tracks = library.songs.filter { track -> uris.any { android.content.ContentUris.parseId(it) == track.id } }.sortedBy { it.title }
            assertEquals(2, tracks.size); assertTrue(tracks.all { it.mime == "audio/wav" })
            tracks.forEach { track ->
                context.contentResolver.query(android.net.Uri.parse(track.uri), arrayOf("artist"), null, null, null)!!.use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    val actual = cursor.getString(0).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                    assertEquals(actual, track.artist) // Providers may replace inserted labels while scanning an untagged WAV.
                }
            }
            withContext(Dispatchers.Main) { coordinator.play(tracks, tracks.first().key) }
            withTimeout(10000) { engine.musicState.first { it.phase == AudioPhase.Playing } }
            withContext(Dispatchers.Main) { engine.musicPlayer.value!!.volume = 0f }
            val future = withContext(Dispatchers.Main) { MediaController.Builder(context, SessionToken(context, ComponentName(context, MusicPlaybackService::class.java))).buildAsync() }
            controller = withContext(Dispatchers.IO) { future.get(10, TimeUnit.SECONDS) }
            val remote = requireNotNull(controller)
            withContext(Dispatchers.Main) {
                assertTrue(remote.isConnected); assertEquals(tracks.first().title, remote.mediaMetadata.title.toString())
                remote.shuffleModeEnabled = true; remote.repeatMode = Player.REPEAT_MODE_ONE
                remote.setPlaybackSpeed(1.25f); remote.seekTo(5000)
            }
            withTimeout(5000) { engine.musicState.first { it.queue.shuffle && it.queue.repeat == MusicRepeat.One && it.queue.speed == 1.25f && it.queue.position >= 4500 } }
            withContext(Dispatchers.Main) { remote.seekToNextMediaItem() }
            withTimeout(5000) { engine.musicState.first { it.current?.key == tracks.last().key && it.phase == AudioPhase.Playing } }
            val nativePlayer = engine.musicPlayer.value
            val queueBeforeAppearanceChange = engine.musicState.value.queue.keys
            // Appearance changes must never rebuild the player or its queue.
            compose.runOnUiThread { presentation.value = presentation.value.copy(accent = 2, glassBlur = 3) }
            compose.waitForIdle()
            assertSame(nativePlayer, engine.musicPlayer.value)
            assertEquals(queueBeforeAppearanceChange, engine.musicState.value.queue.keys)
            assertEquals(AudioPhase.Playing, engine.musicState.value.phase)
            val before = engine.musicState.value.queue.position
            instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            delay(900)
            assertEquals(AudioPhase.Playing, engine.musicState.value.phase)
            assertTrue(engine.musicState.value.queue.position > before)
            withContext(Dispatchers.Main) { remote.pause() }
            withTimeout(5000) { engine.musicState.first { it.phase == AudioPhase.Paused } }
            val parked = engine.musicState.value.queue
            val voice = MediaItem(-100, 100, TelegramMedia(MediaKind.Voice, MediaFile(1), "Voice fixture", "audio/ogg", duration = 3), false)
            withContext(Dispatchers.Main) { engine.loading(voice); engine.stop() }
            assertEquals(parked.keys, engine.musicState.value.queue.keys)
            assertTrue(engine.musicState.value.interrupted)
            assertEquals(AudioPhase.Paused, engine.musicState.value.phase)
            val resolved = repository.resolve(tracks.first()); assertNotNull(resolved)
            context.contentResolver.delete(uris.first { android.content.ContentUris.parseId(it) == tracks.first().id }, null, null)
            assertNull(repository.resolve(tracks.first()))
        } finally {
            withContext(Dispatchers.Main) { controller?.release(); coordinator.stop(); engine.reset() }
            delay(300)
            repository.restoreSettingsForTest(saved)
            subscription.cancel()
            uris.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        }
    }
    @Test fun playlistAndQueuePersistenceUseOnlyLocalIdentifiers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "music-fixture-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
        }
        try {
            val first = MusicPlaylist("fixture", "Fixture list", listOf("device:one", "telegram:123:7", "missing"))
            val state = MusicSettings(listOf(first), listOf("device:one"), MusicQueue(first.keys, 1, 12000, true, MusicRepeat.All, 1.5f))
            MusicPreferences(isolated).save(state)
            assertEquals(state, MusicPreferences(isolated).load())
            assertEquals(listOf("telegram:123:7", "missing", "device:one"), MusicPreferences(isolated).load().playlists.single().move(0, 2).keys)
        } finally { context.deleteSharedPreferences(name) }
    }
    @Test fun deniedAudioPermissionProducesAnHonestEmptyDeviceLibrary() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "music-denied-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int) = context.getSharedPreferences(name, mode)
            override fun checkPermission(permission: String, pid: Int, uid: Int) = if (permission == MusicLibraryRepository.permission) PackageManager.PERMISSION_DENIED else super.checkPermission(permission, pid, uid)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val repo = MusicLibraryRepository(isolated, scope)
            val observed = launch { repo.library.collect {} }
            delay(500)
            assertFalse(repo.library.value.permission); assertTrue(repo.library.value.songs.isEmpty())
            observed.cancel()
        } finally { scope.cancel(); context.deleteSharedPreferences(name) }
    }
}

