package com.vector.messenger.telegram

import android.content.Context
import com.vector.core.media.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject

/** Uses the existing manager. The TDLib download list is the sole persistent download index. */
class TdlibFileClient(private val manager: TdlibClientManager, context: Context) : TelegramFileClient {
    private val root = File(context.noBackupFilesDir, "telegram/files")
    override val updates = manager.mediaUpdates.mapNotNull { json ->
        try { when (json.optString("@type")) {
            "updateFile" -> MediaEvent.File(validate(mapMediaFile(json.getJSONObject("file"))))
            "updateFileAddedToDownloads" -> downloadEntry(json.getJSONObject("file_download"))?.let(MediaEvent::Added)
            "updateFileDownload" -> MediaEvent.Paused(json.getInt("file_id"), json.optBoolean("is_paused"))
            "updateFileRemovedFromDownloads" -> MediaEvent.Removed(json.getInt("file_id"))
            else -> null
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
    }.flowOn(Dispatchers.IO)
    private suspend fun call(json: JSONObject): JSONObject {
        val result = manager.messagingRequest(json)
        check(result.optString("@type") != "error") { "Telegram file request failed" }
        return result
    }
    private suspend fun downloadEntry(json: JSONObject): DownloadEntry? {
        val message = json.optJSONObject("message")?.let(::mapTelegramMessage) ?: return null
        val media = message.media ?: return null
        if (media.file.id != json.optInt("file_id")) return null
        val file = validate(media.file)
        val item = MediaItem(message.chatId, message.id, media, message.canSave)
        return DownloadEntry(item, file, when {
            file.complete -> DownloadPhase.Complete
            json.optBoolean("is_paused") -> DownloadPhase.Paused
            file.active -> DownloadPhase.Downloading
            else -> DownloadPhase.Available
        })
    }
    override suspend fun downloads(offset: String): DownloadPage = withContext(Dispatchers.IO) {
        val result = call(request("searchFileDownloads", "query" to "", "only_active" to false,
            "only_completed" to false, "offset" to offset, "limit" to 50))
        DownloadPage(result.optJSONArray("files")?.objects()?.mapNotNull { downloadEntry(it) }.orEmpty(), result.safeText("next_offset"))
    }
    override suspend fun start(item: MediaItem, managed: Boolean, priority: Int): MediaFile = withContext(Dispatchers.IO) {
        val json = if (managed) call(request("addFileToDownloads", "file_id" to item.media.file.id,
            "chat_id" to item.chatId, "message_id" to item.messageId, "priority" to priority))
        else call(request("downloadFile", "file_id" to item.media.file.id, "priority" to priority,
            "offset" to 0, "limit" to 0, "synchronous" to false))
        if (managed) call(request("toggleDownloadIsPaused", "file_id" to item.media.file.id, "is_paused" to false))
        validate(mapMediaFile(json))
    }
    override suspend fun pause(id: Int, managed: Boolean) {
        if (managed) call(request("toggleDownloadIsPaused", "file_id" to id, "is_paused" to true))
        // downloadFile and the native download list have independent demand.
        call(request("cancelDownloadFile", "file_id" to id, "only_if_pending" to false))
    }
    override suspend fun remove(id: Int) { call(request("removeFileFromDownloads", "file_id" to id, "delete_from_cache" to false)) }
    override suspend fun exportFile(item: MediaItem): MediaFile? = withContext(Dispatchers.IO) {
        if (!item.exportAllowed) return@withContext null
        val message = mapTelegramMessage(call(request("getMessage", "chat_id" to item.chatId, "message_id" to item.messageId)))
        val chat = call(request("getChat", "chat_id" to item.chatId))
        if (!message.canSave || message.restricted || chat.optBoolean("has_protected_content") || restricted(chat) || message.media?.file?.id != item.media.file.id) return@withContext null
        validate(mapMediaFile(call(request("getFile", "file_id" to item.media.file.id)))).takeIf { it.complete && it.path != null }
    }
    override suspend fun validate(file: MediaFile): MediaFile = withContext(Dispatchers.IO) {
        val local = file.path?.let { path -> runCatching { File(path).canonicalFile }.getOrNull() }
        val allowed = local != null && local.toPath().startsWith(root.canonicalFile.toPath()) && local.isFile && local.canRead() && local.length() > 0
        file.copy(path = local?.path?.takeIf { allowed && file.complete }, complete = file.complete && allowed)
    }
}
