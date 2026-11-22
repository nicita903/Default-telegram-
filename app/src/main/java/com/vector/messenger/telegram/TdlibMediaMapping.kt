package com.vector.messenger.telegram

import com.vector.core.media.*
import org.json.JSONObject

/** Raw TDLib paths are metadata only. The file client validates them before UI use. */
internal fun mapMediaFile(json: JSONObject): MediaFile {
    val local = json.optJSONObject("local") ?: JSONObject()
    return MediaFile(json.getInt("id"), json.optLong("size").takeIf { it > 0 } ?: json.optLong("expected_size"),
        local.optLong("downloaded_size"), local.optBoolean("is_downloading_active"),
        local.optBoolean("is_downloading_completed"), local.safeText("path").takeIf(String::isNotBlank),
        local.optBoolean("can_be_downloaded"), json.optJSONObject("remote")?.optLong("uploaded_size") ?: 0,
        json.optJSONObject("remote")?.optBoolean("is_uploading_active") ?: false)
}

internal fun mapTelegramMedia(content: JSONObject): TelegramMedia? {
    if (restricted(content) || content.optBoolean("is_secret") || content.optBoolean("has_spoiler")) return null
    if (content.optString("@type") == "messageVoiceNote") {
        val voice = content.optJSONObject("voice_note") ?: return null
        val file = voice.optJSONObject("voice") ?: return null
        val waveform = runCatching { com.vector.core.audio.unpackWaveform(android.util.Base64.decode(voice.safeText("waveform"), android.util.Base64.DEFAULT)) }.getOrDefault(emptyList())
        return TelegramMedia(MediaKind.Voice, mapMediaFile(file), "Voice message.ogg", voice.safeText("mime_type").ifBlank { "audio/ogg" },
            duration = voice.optInt("duration"), waveform = waveform)
    }
    if (content.optString("@type") == "messageAudio") {
        val audio = content.optJSONObject("audio") ?: return null
        val file = audio.optJSONObject("audio") ?: return null
        return TelegramMedia(MediaKind.Audio, mapMediaFile(file), audio.safeText("title").ifBlank { audio.safeText("file_name").ifBlank { "Audio" } }.take(180),
            audio.safeText("mime_type").ifBlank { "audio/mpeg" }, duration = audio.optInt("duration"), artist = audio.safeText("performer"))
    }
    if (content.optString("@type") == "messagePhoto") {
        val sizes = content.optJSONObject("photo")?.optJSONArray("sizes")?.objects().orEmpty()
        val original = sizes.maxByOrNull { it.optLong("width") * it.optLong("height") } ?: return null
        val small = sizes.filter { it !== original }.minByOrNull { kotlin.math.abs(maxOf(it.optInt("width"), it.optInt("height")) - 320) }
        return TelegramMedia(MediaKind.Photo, mapMediaFile(original.getJSONObject("photo")), "Photo.jpg", "image/jpeg",
            small?.optJSONObject("photo")?.let(::mapMediaFile), original.optInt("width"), original.optInt("height"))
    }
    val (field, fileField, kind, defaultMime) = when (content.optString("@type")) {
        "messageVideo" -> listOf("video", "video", "Video", "video/mp4")
        "messageAnimation" -> listOf("animation", "animation", "Animation", "video/mp4")
        "messageDocument" -> listOf("document", "document", "Document", "application/octet-stream")
        else -> return null
    }
    val media = content.optJSONObject(field) ?: return null
    val file = media.optJSONObject(fileField) ?: return null
    val mime = media.safeText("mime_type").takeIf { it.matches(Regex("[a-zA-Z0-9.+-]+/[a-zA-Z0-9.+-]+")) } ?: defaultMime
    return TelegramMedia(MediaKind.valueOf(kind), mapMediaFile(file),
        media.safeText("file_name").replace(Regex("[\\p{Cntrl}/\\\\]"), "_").take(180).ifBlank { "$kind.${if (mime == "image/gif") "gif" else if (kind == "Document") "bin" else "mp4"}" },
        mime, media.optJSONObject("thumbnail")?.optJSONObject("file")?.let(::mapMediaFile),
        media.optInt("width"), media.optInt("height"), media.optInt("duration"))
}
