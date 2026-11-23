package com.vector.messenger.feature.profile

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.media.MediaMetadataRetriever
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

internal data class PostMedia(val path: String, val thumbnail: String?, val video: Boolean)
internal data class LocalPost(val id: String, val account: Long, val text: String, val created: Long, val media: List<PostMedia>)

/** Provider boundary for future synchronization; these records are never Telegram messages/stories. */
internal interface PostRepository {
    fun observe(account: Long): Flow<List<LocalPost>>
    suspend fun create(account: Long, text: String, uris: List<String>)
    suspend fun delete(account: Long, id: String)
}

@Singleton
internal class LocalPosts @Inject constructor(@ApplicationContext private val context: Context) : PostRepository {
    private val root = File(context.noBackupFilesDir, "profile_posts")
    private val changes = MutableStateFlow(0L)
    private val mutex = Mutex()
    private val helper = object : SQLiteOpenHelper(context, File(root, "posts.db").path, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE posts(id TEXT PRIMARY KEY, account INTEGER NOT NULL, body TEXT NOT NULL, created INTEGER NOT NULL, media TEXT NOT NULL)")
            db.execSQL("CREATE INDEX posts_account_date ON posts(account,created DESC)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    override fun observe(account: Long): Flow<List<LocalPost>> = changes.mapLatest { withContext(Dispatchers.IO) {
        root.mkdirs()
        helper.readableDatabase.query("posts", arrayOf("id", "body", "created", "media"), "account=?", arrayOf(account.toString()), null, null, "created DESC, id DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    ensureActive()
                    val id = cursor.getString(0)
                    val directory = directory(account, id)
                    val json = JSONArray(cursor.getString(3))
                    val media = (0 until json.length()).mapNotNull { index ->
                        val value = json.getJSONObject(index)
                        fun file(key: String) = value.optString(key).takeIf { it.isNotBlank() && it != "." && it != ".." && !it.contains('/') && !it.contains('\\') }?.let { File(directory, it).canonicalFile }?.takeIf { it.toPath().startsWith(directory.canonicalFile.toPath()) }?.path
                        file("file")?.let { PostMedia(it, file("thumbnail"), value.optBoolean("video")) }
                    }
                    add(LocalPost(id, account, cursor.getString(1), cursor.getLong(2), media))
                }
            }
        }
    } }
    private fun directory(account: Long, id: String): File {
        require(account > 0 && runCatching { UUID.fromString(id) }.isSuccess)
        return File(File(root, account.toString()), id)
    }
    override suspend fun create(account: Long, text: String, uris: List<String>) = mutex.withLock { withContext(Dispatchers.IO) {
        require(account > 0 && uris.size <= 10 && (text.isNotBlank() || uris.isNotEmpty()))
        val id = UUID.randomUUID().toString()
        val directory = directory(account, id).apply { check(mkdirs()) }
        var committed = false
        try {
            var total = 0L
            val json = JSONArray()
            for ((index, value) in uris.withIndex()) {
                ensureActive()
                val uri = Uri.parse(value)
                require(uri.scheme == "content")
                val mime = context.contentResolver.getType(uri).orEmpty()
                require(mime.startsWith("image/") || mime.startsWith("video/")) { "Choose an image or video." }
                val video = mime.startsWith("video/")
                val extension = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: if (video) "mp4" else "img"
                val file = File(directory, "$index.$extension")
                context.contentResolver.openInputStream(uri)?.use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var bytes = 0L
                    while (true) {
                        ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        bytes += count; total += count
                        require(bytes <= 500_000_000L && total <= 1_000_000_000L) { "Media is too large for a local post (500 MB per file, 1 GB total)." }
                        output.write(buffer, 0, count)
                    }
                    require(bytes > 0) { "The selected media is empty." }
                } } ?: error("The selected media is unavailable.")
                var thumbnail: String? = null
                if (video) {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(file.path)
                        retriever.getScaledFrameAtTime(-1, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)?.let { bitmap ->
                            try {
                                val preview = File(directory, "$index-preview.jpg")
                                preview.outputStream().use { check(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 85, it)) }
                                thumbnail = preview.name
                            } finally { bitmap.recycle() }
                        }
                    } catch (_: Exception) { /* A playable video can lack a preview frame. */ }
                    finally { retriever.release() }
                }
                json.put(JSONObject().put("file", file.name).put("thumbnail", thumbnail ?: "").put("video", video))
            }
            ensureActive()
            val values = ContentValues().apply { put("id", id); put("account", account); put("body", text); put("created", System.currentTimeMillis()); put("media", json.toString()) }
            helper.writableDatabase.insertOrThrow("posts", null, values)
            committed = true
            changes.update { it + 1 }
        } finally { if (!committed) directory.deleteRecursively() }
    } }
    override suspend fun delete(account: Long, id: String) = mutex.withLock { withContext(Dispatchers.IO) {
        val directory = directory(account, id)
        if (helper.writableDatabase.delete("posts", "id=? AND account=?", arrayOf(id, account.toString())) > 0) {
            changes.update { it + 1 }
            directory.deleteRecursively()
        }
        Unit
    } }
}
