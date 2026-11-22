package com.vector.messenger

import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.media.*
import com.vector.messenger.telegram.*
import com.vector.messenger.feature.media.TelegramContentProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TelegramMediaMappingTest {
    private fun file(id: Int) = JSONObject().put("id", id).put("size", 5000).put("expected_size", 6000)
        .put("local", JSONObject().put("downloaded_size", 2000).put("can_be_downloaded", true).put("is_downloading_active", true))
    private fun message(content: JSONObject) = JSONObject().put("id", 10).put("chat_id", -100).put("can_be_saved", true).put("content", content)
    @Test fun photosChooseOriginalAndSeparateSampledThumbnail() {
        val sizes = JSONArray().put(JSONObject().put("width", 160).put("height", 160).put("photo", file(1)))
            .put(JSONObject().put("width", 320).put("height", 320).put("photo", file(2)))
            .put(JSONObject().put("width", 4000).put("height", 3000).put("photo", file(3)))
        val content = JSONObject().put("@type", "messagePhoto").put("photo", JSONObject().put("sizes", sizes))
        val media = mapTelegramMessage(message(content)).media!!
        assertEquals(MediaKind.Photo, media.kind); assertEquals(3, media.file.id); assertEquals(2, media.thumbnail!!.id)
        assertEquals(4000, media.width); assertEquals(5000L, media.file.size); assertEquals(2000L, media.file.downloaded)
        assertEquals(6000L, mapMediaFile(file(9).put("size", 0)).size)
    }
    @Test fun videoAnimationAndDocumentsHaveSafeNamesTypesAndDimensions() {
        listOf("messageVideo" to "video", "messageAnimation" to "animation", "messageDocument" to "document").forEach { (type, field) ->
            val detail = JSONObject().put(field, file(5)).put("file_name", "../bad\nname.mp4").put("mime_type", if (field == "animation") "image/gif" else "video/mp4")
                .put("duration", 42).put("width", 1920).put("height", 1080)
                .put("thumbnail", JSONObject().put("file", file(6)))
            val media = mapTelegramMedia(JSONObject().put("@type", type).put(field, detail))!!
            assertFalse(media.name.contains('/')); assertFalse(media.name.contains('\n'))
            assertEquals(5, media.file.id); assertEquals(6, media.thumbnail!!.id); assertEquals(42, media.duration)
            if (field == "animation") assertEquals("image/gif", media.mime)
        }
    }
    @Test fun spoilerSecretAndEphemeralMediaAreNotExposed() {
        val content = JSONObject().put("@type", "messageDocument").put("document", JSONObject().put("document", file(5)))
        assertNotNull(mapTelegramMedia(content))
        content.put("has_spoiler", true); assertNull(mapTelegramMedia(content))
        content.remove("has_spoiler"); content.put("is_secret", true); assertNull(mapTelegramMedia(content))
        content.remove("is_secret"); val json = message(content).put("self_destruct_type", JSONObject())
        assertNull(mapTelegramMessage(json).media)
    }
    @Test fun contentUpdateCarriesMediaReplacementAndRemoval() {
        val content = JSONObject().put("@type", "messageDocument").put("document", JSONObject().put("document", file(5)))
        val update = JSONObject().put("@type", "updateMessageContent").put("chat_id", -100).put("message_id", 10).put("new_content", content)
        assertEquals(5, (mapChatUpdate(update) { null } as com.vector.core.messaging.ChatUpdate.Content).media!!.file.id)
        update.put("new_content", JSONObject().put("@type", "messageText").put("text", JSONObject().put("text", "Fixture")))
        assertNull((mapChatUpdate(update) { null } as com.vector.core.messaging.ChatUpdate.Content).media)
    }
    @Test fun fileValidationRejectsMissingAndOutsidePrivateRoot() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val client = TdlibFileClient(TdlibClientManager(context, scope), context)
        val outside = File(context.cacheDir, "media-fixture-${UUID.randomUUID()}")
        try {
            outside.writeText("Synthetic fixture")
            val result = client.validate(MediaFile(1, complete = true, path = outside.path))
            assertFalse(result.complete); assertNull(result.path)
            val missing = client.validate(MediaFile(1, complete = true, path = File(context.noBackupFilesDir, "telegram/files/not-present-${UUID.randomUUID()}").path))
            assertFalse(missing.complete); assertNull(missing.path)
        } finally { outside.delete(); scope.cancel() }
    }
    @Test fun contentUrisAreReadOnlyRevocableAndConfined() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "telegram/files/media-test").apply { mkdirs() }
        val fixture = File(directory, UUID.randomUUID().toString())
        try {
            fixture.writeText("Synthetic fixture")
            val uri = TelegramContentProvider.uri(context, fixture.path, "Fixture.txt", "text/plain")
            assertFalse(uri.toString().contains(fixture.path)); assertFalse(uri.toString().contains("Fixture.txt"))
            assertEquals("text/plain", context.contentResolver.getType(uri))
            context.contentResolver.openInputStream(uri)!!.use { assertEquals("Synthetic fixture", it.bufferedReader().readText()) }
            assertTrue(runCatching { context.contentResolver.openFileDescriptor(uri, "w") }.isFailure)
            TelegramContentProvider.clear()
            assertTrue(runCatching { context.contentResolver.openInputStream(uri) }.isFailure)
            val outside = TelegramContentProvider.uri(context, File(context.cacheDir, "outside").path, "Fixture.txt", "text/plain")
            assertTrue(runCatching { context.contentResolver.openInputStream(outside) }.isFailure)
        } finally { fixture.delete(); directory.delete(); TelegramContentProvider.clear() }
    }
}
