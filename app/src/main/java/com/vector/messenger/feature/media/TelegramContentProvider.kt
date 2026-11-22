package com.vector.messenger.feature.media

import android.content.*
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Read-only, expiring content grants. No URI contains a path, file ID or account ID. */
class TelegramContentProvider : ContentProvider() {
    private data class Grant(val path: String, val name: String, val mime: String, val expires: Long)
    companion object {
        private val grants = ConcurrentHashMap<String, Grant>()
        fun clear() { grants.clear() }
        fun uri(context: Context, path: String, name: String, mime: String): Uri {
            val now = SystemClock.elapsedRealtime()
            grants.entries.removeIf { it.value.expires < now }
            if (grants.size >= 64) grants.clear()
            val token = UUID.randomUUID().toString()
            grants[token] = Grant(path, name, mime, now + 10 * 60_000)
            return Uri.Builder().scheme("content").authority("${context.packageName}.media").appendPath(token).build()
        }
    }
    override fun onCreate() = true
    private fun grant(uri: Uri): Pair<Grant, File> {
        val grant = grants[uri.lastPathSegment] ?: throw FileNotFoundException("File unavailable")
        if (grant.expires < SystemClock.elapsedRealtime()) throw FileNotFoundException("File unavailable")
        val root = File(requireNotNull(context).noBackupFilesDir, "telegram/files").canonicalFile
        val file = File(grant.path).canonicalFile
        if (!file.toPath().startsWith(root.toPath()) || !file.isFile || !file.canRead()) throw FileNotFoundException("File unavailable")
        return grant to file
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Read only")
        return ParcelFileDescriptor.open(grant(uri).second, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri) = grant(uri).first.mime
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val (grant, file) = grant(uri)
        val columns = projection?.filter { it == OpenableColumns.DISPLAY_NAME || it == OpenableColumns.SIZE }?.toTypedArray()
            ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { if (it == OpenableColumns.SIZE) file.length() else grant.name }) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read only")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
