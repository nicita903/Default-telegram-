package com.vector.core

/** Provider/account-qualified contact and activity data; approximate presence stays approximate. */
data class ConversationIdentity(val providerId: String, val accountId: String, val subjectId: String)
enum class PresenceKind { Online, Offline, Recently, WithinWeek, WithinMonth, Unknown }
data class ContactPresence(val identity: ConversationIdentity, val kind: PresenceKind, val wasOnline: Long? = null, val expires: Long? = null)
enum class ChatActivityKind { Typing, RecordingVoice, RecordingVideo, UploadingPhoto, UploadingVideo, UploadingDocument, UploadingVoice, UploadingVideoNote, ChoosingSticker, ChoosingLocation, ChoosingContact, PlayingGame }
data class ConversationSubtitle(val text: String = "", val online: Boolean = false, val activity: Boolean = false)
data class MessageArrival(val session: Long, val chatId: Long, val messageId: Long, val outgoing: Boolean, val followLatest: Boolean, val localSend: Boolean = false)
