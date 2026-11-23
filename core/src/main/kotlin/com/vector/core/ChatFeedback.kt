package com.vector.core

/** Actual repository events, independent of Android playback. No replay of history or status scans. */
enum class ChatFeedbackKind { Accepted, Incoming, Failed, Confirmation }
data class ChatFeedback(val session: Long, val chatId: Long, val key: String, val kind: ChatFeedbackKind, val date: Long = 0)
