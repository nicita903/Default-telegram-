package com.vector.messenger.feature.search

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.MediaCodecList
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.vector.core.media.*
import com.vector.core.music.*
import com.vector.core.profile.ProfileRepository
import com.vector.messenger.feature.music.MusicPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.*

typealias LocalSong = MusicTrack
data class MusicLibrary(val songs: List<MusicTrack> = emptyList(), val playlists: List<MusicPlaylist> = emptyList(),
    val permission: Boolean = false, val error: String? = null, val recent: List<String> = emptyList())

/** One shared MediaStore catalogue, merged with eligible private TDLib downloads, never copied. */
@Singleton
class MusicLibraryRepository @Inject constructor(@ApplicationContext private val context: Context,
    private val scope: CoroutineScope, private val media: MediaRepository? = null, private val profile: ProfileRepository? = null) {
    companion object { val permission: String get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE }
    private val refresh = MutableStateFlow(0)
    private val preferences = MusicPreferences(context)
    private val settingsState = MutableStateFlow(MusicSettings())
    val settings = settingsState.asStateFlow()
    private val loaded = CompletableDeferred<Unit>()
    private val edits = Mutex()
    private val privateMetadata = linkedMapOf<String, Map<Int, String>>()
    private fun tags(path: String): Map<Int, String> {
        val key = "$path:${java.io.File(path).lastModified()}"
        privateMetadata[key]?.let { return it }
        val reader = android.media.MediaMetadataRetriever()
        val tags = try {
            reader.setDataSource(path)
            listOf(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE, android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST,
                android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM, android.media.MediaMetadataRetriever.METADATA_KEY_DURATION,
                android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).associateWith { reader.extractMetadata(it).orEmpty() }
        } catch (_: Exception) { emptyMap() } finally { reader.release() }
        privateMetadata[key] = tags
        while (privateMetadata.size > 512) privateMetadata.remove(privateMetadata.keys.first())
        return tags
    }
    init { scope.launch { settingsState.value = withContext(Dispatchers.IO) { preferences.load() }; loaded.complete(Unit) } }
    fun refresh() { refresh.value++ }
    private val codecs by lazy { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filterNot { it.isEncoder }.flatMap { it.supportedTypes.toList() }.toSet() }
    private fun supported(mime: String) = when (mime) {
        "audio/wav" -> true
        "audio/mp4", "audio/mp4a-latm" -> "audio/mp4a-latm" in codecs
        "audio/ogg" -> "audio/vorbis" in codecs || "audio/opus" in codecs
        else -> mime in codecs
    }
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private val device = combine(refresh, callbackFlow {
        val observer = object : ContentObserver(null) { override fun onChange(selfChange: Boolean) { trySend(Unit) } }
        context.contentResolver.registerContentObserver(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, true, observer)
        trySend(Unit)
        awaitClose { context.contentResolver.unregisterContentObserver(observer) }
    }.conflate().debounce(150)) { _, _ -> Unit }.mapLatest { withContext(Dispatchers.IO) { scan() } }

    private val privateTracks: Flow<List<MusicTrack>> = if (media == null || profile == null) flowOf(emptyList()) else combine(
        media.state.map { Triple(it.authenticated, it.session, it.downloads.filter { entry -> entry.phase == DownloadPhase.Complete && entry.item.exportAllowed &&
            (entry.item.media.kind == MediaKind.Audio || (entry.item.media.kind == MediaKind.Document && musicMime(entry.item.media.mime, entry.item.media.name) != null)) }) }.distinctUntilChanged(),
        profile.state.map { if (it.authenticated) it.profile?.id else null }.distinctUntilChanged(),
    ) { source, account ->
        if (!source.first || account == null) emptyList() else source.third.mapNotNull { entry ->
            val descriptor = entry.item.media
            val mime = musicMime(descriptor.mime, descriptor.name) ?: return@mapNotNull null
            val path = entry.file.path ?: return@mapNotNull null
            val tags = tags(path)
            MusicTrack(-entry.file.id.toLong(), tags[android.media.MediaMetadataRetriever.METADATA_KEY_TITLE]?.takeIf(String::isNotBlank) ?: descriptor.name,
                descriptor.artist.ifBlank { tags[android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST].orEmpty() },
                tags[android.media.MediaMetadataRetriever.METADATA_KEY_ALBUM].orEmpty(), android.net.Uri.fromFile(java.io.File(path)).toString(), java.io.File(path).lastModified() / 1000,
                tags[android.media.MediaMetadataRetriever.METADATA_KEY_DURATION]?.toLongOrNull() ?: descriptor.duration * 1000L,
                tags[android.media.MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER]?.substringBefore('/')?.toIntOrNull() ?: 0,
                folder = "Telegram downloads", mime = mime, key = "telegram:$account:${entry.file.id}", telegram = entry.item)
        }
    }.flowOn(Dispatchers.IO)

    val library: StateFlow<MusicLibrary> = combine(device, privateTracks,
        settings.map { it.playlists to it.recent }.distinctUntilChanged()) { local, telegram, saved ->
        local.copy(songs = (local.songs + telegram).distinctBy { it.key }, playlists = saved.first + local.playlists, recent = saved.second)
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.WhileSubscribed(5000), MusicLibrary())

    private fun edit(change: (MusicSettings) -> MusicSettings) { scope.launch {
        loaded.await(); edits.withLock {
            val next = change(settings.value)
            withContext(Dispatchers.IO) { preferences.save(next) }; settingsState.value = next
        }
    } }
    fun playlist(name: String) { val clean = name.trim().take(80); if (clean.isNotEmpty()) edit { it.copy(playlists = (it.playlists + MusicPlaylist(java.util.UUID.randomUUID().toString(), clean, emptyList())).take(200)) } }
    fun rename(id: String, name: String) { val clean = name.trim().take(80); if (clean.isNotEmpty()) edit { it.copy(playlists = it.playlists.map { list -> if (list.id == id && !list.readOnly) list.copy(name = clean) else list }) } }
    fun delete(id: String) = edit { it.copy(playlists = it.playlists.filterNot { list -> list.id == id && !list.readOnly }) }
    fun add(id: String, key: String) = edit { it.copy(playlists = it.playlists.map { list -> if (list.id == id && !list.readOnly) list.copy(keys = (list.keys + key).distinct().take(1000)) else list }) }
    fun remove(id: String, key: String) = edit { it.copy(playlists = it.playlists.map { list -> if (list.id == id && !list.readOnly) list.copy(keys = list.keys - key) else list }) }
    fun reorder(id: String, from: Int, to: Int) = edit { it.copy(playlists = it.playlists.map { list -> if (list.id == id) list.move(from, to) else list }) }
    fun queue(value: MusicQueue) = edit { it.copy(queue = value.normalized()) }
    fun played(key: String) = edit { it.copy(recent = (listOf(key) + it.recent).distinct().take(100)) }
    internal suspend fun restoreSettingsForTest(value: MusicSettings) {
        loaded.await(); edits.withLock { withContext(Dispatchers.IO) { preferences.save(value) }; settingsState.value = value }
    }
    internal suspend fun settingsSnapshotForTest(): MusicSettings { loaded.await(); return settings.value }

    suspend fun resolve(track: MusicTrack): String? = withContext(Dispatchers.IO) {
        if (track.telegram != null) {
            val current = library.value.songs.find { it.key == track.key && it.telegram != null } ?: return@withContext null
            val file = media?.exportFile(requireNotNull(current.telegram)) ?: return@withContext null
            file.path?.let { android.net.Uri.fromFile(java.io.File(it)).toString() }
        } else try {
            val uri = android.net.Uri.parse(track.uri)
            if (uri.scheme != "content" || uri.authority != MediaStore.AUTHORITY) return@withContext null
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { track.uri }
        } catch (_: Exception) { null }
    }

    @Suppress("DEPRECATION")
    private suspend fun scan(): MusicLibrary {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) return MusicLibrary()
        return try {
            val songs = mutableListOf<MusicTrack>()
            val projection = arrayOf("_id", "title", "artist", "album", "date_added", "duration", "track", "album_id", "relative_path", "mime_type", "_display_name", "volume_name")
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "is_music != 0", null, "title COLLATE NOCASE ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    if (songs.size % 64 == 0) currentCoroutineContext().ensureActive()
                    val mime = musicMime(cursor.getString(9).orEmpty(), cursor.getString(10).orEmpty()) ?: continue
                    if (!supported(mime)) continue
                    val id = cursor.getLong(0)
                    fun text(index: Int) = cursor.getString(index).orEmpty().takeUnless { it == "<unknown>" }.orEmpty()
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.getContentUri(text(11).ifBlank { "external" }), id).toString()
                    songs += MusicTrack(id, text(1).ifBlank { text(10).ifBlank { "Untitled audio" } }, text(2), text(3), uri, cursor.getLong(4),
                        cursor.getLong(5), cursor.getInt(6) % 1000, text(8).trimEnd('/').ifBlank { "On device" },
                        cursor.getLong(7).takeIf { it > 0 }?.let { "content://media/external/audio/albumart/$it" }, mime)
                }
            }
            val lists = mutableListOf<MusicPlaylist>()
            val keys = songs.associate { it.id to it.key }
            try {
                context.contentResolver.query(MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, arrayOf("_id", "name"), null, null, "name ASC")?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(0); val members = mutableListOf<String>()
                        context.contentResolver.query(MediaStore.Audio.Playlists.Members.getContentUri("external", id), arrayOf("audio_id"), null, null, "play_order ASC")?.use { items ->
                            while (items.moveToNext()) keys[items.getLong(0)]?.let(members::add)
                        }
                        lists += MusicPlaylist("system:$id", cursor.getString(1).orEmpty(), members, readOnly = true)
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Some Android providers no longer expose system playlists. Local lists remain functional. */ }
            MusicLibrary(songs, lists, permission = true)
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: SecurityException) { MusicLibrary(error = "Audio permission changed. Allow audio access again.") }
        catch (_: Exception) { MusicLibrary(permission = true, error = "The device music catalogue is unavailable. Refresh to retry.") }
    }
}
