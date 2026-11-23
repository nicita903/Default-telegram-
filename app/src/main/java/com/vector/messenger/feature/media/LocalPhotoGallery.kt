package com.vector.messenger.feature.media

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.*
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

enum class GalleryAccess { Denied, Limited, Full }
data class LocalPhoto(val uri: String, val id: Long, val added: Long, val modified: Long)
data class PhotoFolder(val key: String, val name: String, val count: Int, val collection: String? = null, val bucket: String? = null)
data class LocalGalleryState(val access: GalleryAccess = GalleryAccess.Denied, val folders: List<PhotoFolder> = emptyList(),
    val folder: PhotoFolder? = null, val photos: List<LocalPhoto> = emptyList(), val loading: Boolean = false,
    val more: Boolean = true, val error: String? = null, val revision: Int = 0)

class LocalPhotoGallery @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        const val PageSize = 60
        val permissions: Array<String> get() = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }
    private val collections = listOf(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL), MediaStore.Images.Media.INTERNAL_CONTENT_URI)
    fun access(): GalleryAccess {
        fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return when {
            granted(if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) -> GalleryAccess.Full
            Build.VERSION.SDK_INT >= 34 && granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) -> GalleryAccess.Limited
            else -> GalleryAccess.Denied
        }
    }
    private val selection get() = "${MediaStore.MediaColumns.SIZE}>0 AND ${MediaStore.MediaColumns.IS_PENDING}=0" +
        if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.MediaColumns.IS_TRASHED}=0" else ""
    private suspend fun <T> query(uri: Uri, columns: Array<String>, args: Bundle, read: (Cursor, CancellationSignal) -> T): T? = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val signal = CancellationSignal()
            continuation.invokeOnCancellation { signal.cancel() }
            try {
                val result = context.contentResolver.query(uri, columns, args, signal)?.use { read(it, signal) }
                if (continuation.isActive) continuation.resume(result)
            } catch (failure: Exception) { if (continuation.isActive) continuation.resumeWithException(failure) }
        }
    }
    suspend fun folders(): List<PhotoFolder> {
        val result = mutableListOf<PhotoFolder>()
        for (collection in collections) {
            val counts = try { query(collection, arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                Bundle().apply { putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection) }) { cursor, signal ->
                val buckets = linkedMapOf<String, Pair<String, Int>>()
                while (cursor.moveToNext()) {
                    signal.throwIfCanceled()
                    val id = cursor.getString(0).orEmpty()
                    val name = cursor.getString(1)?.takeIf { it.isNotBlank() } ?: "Other photos"
                    buckets[id] = name to ((buckets[id]?.second ?: 0) + 1)
                }
                buckets
            }.orEmpty() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { emptyMap() }
            counts.forEach { (id, value) -> result += PhotoFolder("$collection|$id", value.first, value.second, collection.toString(), id) }
        }
        return listOf(PhotoFolder("all", "All Photos", result.sumOf { it.count })) + result.sortedBy { it.name.lowercase() }
    }
    suspend fun page(folder: PhotoFolder?, after: LocalPhoto?): List<LocalPhoto> {
        val result = mutableListOf<LocalPhoto>()
        for (collection in collections.filter { folder?.collection == null || it.toString() == folder.collection }) {
            val clauses = mutableListOf(selection)
            val values = mutableListOf<String>()
            folder?.bucket?.let {
                if (it.isEmpty()) clauses += "(${MediaStore.Images.Media.BUCKET_ID} IS NULL OR ${MediaStore.Images.Media.BUCKET_ID}='')"
                else { clauses += "${MediaStore.Images.Media.BUCKET_ID}=?"; values += it }
            }
            after?.let {
                clauses += "(${MediaStore.MediaColumns.DATE_ADDED}<? OR (${MediaStore.MediaColumns.DATE_ADDED}=? AND ${MediaStore.MediaColumns._ID}<=?))"
                values += listOf(it.added.toString(), it.added.toString(), it.id.toString())
            }
            val args = Bundle().apply {
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, clauses.joinToString(" AND "))
                putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, values.toTypedArray())
                putString(android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns.DATE_ADDED} DESC, ${MediaStore.MediaColumns._ID} DESC")
                putInt(android.content.ContentResolver.QUERY_ARG_LIMIT, PageSize + 2)
            }
            result += try { query(collection, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DATE_MODIFIED), args) { cursor, signal ->
                buildList {
                    while (size < PageSize + 2 && cursor.moveToNext()) {
                        signal.throwIfCanceled()
                        val id = cursor.getLong(0)
                        add(LocalPhoto(ContentUris.withAppendedId(collection, id).toString(), id, cursor.getLong(1), cursor.getLong(2)))
                    }
                }
            }.orEmpty() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: SecurityException) { emptyList() }
        }
        val order = compareByDescending<LocalPhoto> { it.added }.thenByDescending { it.id }.thenByDescending { it.uri }
        return result.distinctBy { it.uri }.filter { after == null || order.compare(it, after) > 0 }.sortedWith(order).take(PageSize + 1)
    }
    suspend fun accessible(uris: List<String>): List<String> {
        if (access() == GalleryAccess.Denied) return emptyList()
        return uris.filter { value ->
            val uri = Uri.parse(value)
            try { uri.authority == MediaStore.AUTHORITY && query(uri, arrayOf(MediaStore.MediaColumns._ID), Bundle()) { cursor, _ -> cursor.moveToFirst() } == true }
            catch (_: SecurityException) { false }
        }
    }
    fun observe(changed: () -> Unit): () -> Unit {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) { override fun onChange(selfChange: Boolean) { changed() } }
        collections.forEach { context.contentResolver.registerContentObserver(it, true, observer) }
        return { context.contentResolver.unregisterContentObserver(observer) }
    }
    suspend fun thumbnail(photo: LocalPhoto, revision: Int) = MediaImages.loadGalleryThumbnail(context, photo.uri, photo.modified, revision)?.bitmap
}

class LocalGalleryController(private val library: LocalPhotoGallery, private val scope: CoroutineScope) {
    private val mutable = MutableStateFlow(LocalGalleryState())
    val state = mutable.asStateFlow()
    private var pageJob: Job? = null
    private var foldersJob: Job? = null
    private var refreshJob: Job? = null
    fun refresh() {
        pageJob?.cancel(); foldersJob?.cancel()
        val access = library.access()
        mutable.update { it.copy(access = access, photos = emptyList(), folders = emptyList(), more = true, loading = false, error = null, revision = it.revision + 1) }
        if (access == GalleryAccess.Denied) return
        foldersJob = scope.launch {
            try {
                val folders = library.folders(); ensureActive()
                mutable.update { current -> current.copy(folders = folders,
                    folder = current.folder?.let { old -> folders.find { it.key == old.key } ?: old }) }
                if (state.value.folder != null && folders.none { it.key == state.value.folder?.key }) folder(folders.first())
            }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(error = "Unable to read local folders. Check photo access.") } }
        }
        more()
    }
    fun changed() { refreshJob?.cancel(); refreshJob = scope.launch { delay(200); refresh() } }
    fun folder(folder: PhotoFolder) {
        pageJob?.cancel()
        mutable.update { it.copy(folder = folder, photos = emptyList(), more = true, loading = false, error = null) }
        more()
    }
    fun more() {
        if (pageJob?.isActive == true || !state.value.more || state.value.access == GalleryAccess.Denied) return
        val folder = state.value.folder
        val cursor = state.value.photos.lastOrNull()
        mutable.update { it.copy(loading = true) }
        pageJob = scope.launch {
            try {
                val page = library.page(folder, cursor); ensureActive()
                mutable.update { it.copy(photos = (it.photos + page.take(LocalPhotoGallery.PageSize)).distinctBy { photo -> photo.uri },
                    more = page.size > LocalPhotoGallery.PageSize, loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutable.update { it.copy(loading = false, more = false, error = "Unable to load photos. Refresh or change photo access.") } }
        }
    }
    fun observe(changed: () -> Unit) = library.observe(changed)
    suspend fun accessible(uris: List<String>) = library.accessible(uris)
    suspend fun thumbnail(photo: LocalPhoto, revision: Int) = library.thumbnail(photo, revision)
    fun stop() { pageJob?.cancel(); foldersJob?.cancel(); refreshJob?.cancel() }
}
