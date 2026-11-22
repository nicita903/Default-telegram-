package com.vector.core.media

import com.vector.core.SendResult

/** Prepared private JPEG; TDLib receives a local file, never an Android content URI. */
data class UploadPhoto(val path: String, val width: Int, val height: Int) {
    override fun toString() = "UploadPhoto([redacted])"
}
data class PhotoSendResult(val accepted: Int = 0, val result: SendResult = SendResult.Failed)
