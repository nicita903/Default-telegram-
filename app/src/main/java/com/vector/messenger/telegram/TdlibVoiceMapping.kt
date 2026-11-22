package com.vector.messenger.telegram

import android.util.Base64
import com.vector.core.audio.*

/** Matches the installed pinned TDLib's nested InputVoiceNote schema. */
internal fun voiceMessageRequest(chatId: Long, clip: VoiceClip, replyTo: Long?) = request("sendMessage",
    "chat_id" to chatId, "topic_id" to null,
    "reply_to" to replyTo?.let { request("inputMessageReplyToMessage", "message_id" to it, "quote" to null, "checklist_task_id" to 0, "poll_option_id" to "") },
    "options" to null, "reply_markup" to null, "input_message_content" to request("inputMessageVoiceNote",
        "voice_note" to request("inputVoiceNote", "voice_note" to request("inputFileLocal", "path" to clip.path),
            "duration" to clip.duration, "waveform" to Base64.encodeToString(packWaveform(clip.waveform), Base64.NO_WRAP)),
        "caption" to null, "self_destruct_type" to null))
