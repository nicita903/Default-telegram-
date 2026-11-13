package com.vector.messenger.feature.search

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext

data class LocalSong(val id: Long, val title: String, val artist: String, val album: String, val uri: String, val added: Long)
data class LocalPlaylist(val id: Long, val name: String, val songIds: List<Long>)
data class MusicLibrary(val songs: List<LocalSong> = emptyList(), val playlists: List<LocalPlaylist> = emptyList(), val permission: Boolean = false, val error: String? = null)

/** Read-only MediaStore access. No fabricated tracks, broad file access, or playback service. */
class MusicLibraryRepository @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        val permission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    }
    private val refresh = MutableStateFlow(0)
    fun refresh() { refresh.value++ }
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val library: Flow<MusicLibrary> = combine(refresh, callbackFlow {
        val observer = object : ContentObserver(null) { override fun onChange(selfChange: Boolean) { trySend(Unit) } }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }) { _, _ -> Unit }.mapLatest { withContext(Dispatchers.IO) { readLibrary() } }

    @Suppress("DEPRECATION")
    private fun readLibrary(): MusicLibrary {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return MusicLibrary()
        return try {
            val resolver = context.contentResolver
            val songs = mutableListOf<LocalSong>()
            resolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf("_id", "title", "artist", "album", "date_added"), "is_music != 0", null, "title COLLATE NOCASE ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    songs += LocalSong(id, cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty(), cursor.getString(3).orEmpty(),
                        ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(), cursor.getLong(4))
                }
            }
            val playlists = mutableListOf<LocalPlaylist>()
            var playlistWarning: String? = null
            try {
            resolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, arrayOf("_id", "name"), null, null, "name ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val members = mutableListOf<Long>()
                    resolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", id), arrayOf("audio_id"), null, null, "play_order ASC")?.use { items ->
                        while (items.moveToNext()) members += items.getLong(0)
                    }
                    playlists += LocalPlaylist(id, cursor.getString(1).orEmpty(), members)
                }
            }
            } catch (_: IllegalArgumentException) { playlistWarning = "This audio provider does not expose playlists." }
            catch (_: android.database.sqlite.SQLiteException) { playlistWarning = "Playlists are unavailable from this audio provider." }
            MusicLibrary(songs, playlists, permission = true, error = playlistWarning)
        } catch (_: SecurityException) { MusicLibrary(error = "Audio permission changed. Allow access to read your library.") }
        catch (_: IllegalArgumentException) { MusicLibrary(permission = true, error = "This device's audio provider does not support this library query.") }
        catch (_: android.database.sqlite.SQLiteException) { MusicLibrary(permission = true, error = "The device audio library is temporarily unavailable.") }
    }
}
