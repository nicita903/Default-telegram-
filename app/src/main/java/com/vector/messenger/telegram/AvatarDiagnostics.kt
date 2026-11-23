package com.vector.messenger.telegram

internal enum class AvatarStage {
    MetadataPresent, MetadataAbsent, FileIdMissing, DownloadRequested, DownloadAccepted,
    DownloadFailed, FileIncomplete, FileReady, FileRejected, DecodeReady, DecodeFailed,
}

