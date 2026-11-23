package com.vector.core.media

import com.vector.core.SendResult

/** Prepared private JPEG; TDLib receives a local file, never an Android content URI. */
enum class PhotoQuality { STANDARD, HD, FILE }
data class UploadPhoto(val path: String, val width: Int, val height: Int, val document: Boolean = false) {
    override fun toString() = "UploadPhoto([redacted])"
}
data class PhotoSendResult(val accepted: Int = 0, val result: SendResult = SendResult.Failed)
