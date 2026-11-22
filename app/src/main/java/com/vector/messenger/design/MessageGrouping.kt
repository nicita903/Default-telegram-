package com.vector.messenger.design

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.vector.core.Message
import java.time.Instant
import java.time.ZoneId

enum class MessageGroupPosition { SINGLE, FIRST, MIDDLE, LAST }

object MessageGrouping {
    const val WindowSeconds = 300L
    val NormalCorner = 18.dp
    val ConnectedCorner = 5.dp
    fun connects(older: Message?, newer: Message?, thresholdSeconds: Long = WindowSeconds): Boolean {
        if (older == null || newer == null || older.isService || newer.isService ||
            older.chatId != newer.chatId || older.senderId == 0L || older.senderId != newer.senderId ||
            older.senderIsChat != newer.senderIsChat || older.outgoing != newer.outgoing || older.date <= 0 ||
            newer.date - older.date !in 0..thresholdSeconds) return false
        val zone = ZoneId.systemDefault()
        return Instant.ofEpochSecond(older.date).atZone(zone).toLocalDate() == Instant.ofEpochSecond(newer.date).atZone(zone).toLocalDate()
    }
    fun position(previous: Message?, message: Message, next: Message?): MessageGroupPosition {
        val before = connects(previous, message)
        val after = connects(message, next)
        return when { before && after -> MessageGroupPosition.MIDDLE; before -> MessageGroupPosition.LAST
            after -> MessageGroupPosition.FIRST; else -> MessageGroupPosition.SINGLE }
    }
    fun shape(position: MessageGroupPosition, outgoing: Boolean): RoundedCornerShape {
        val top = if (position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.LAST) ConnectedCorner else NormalCorner
        val bottom = if (position == MessageGroupPosition.MIDDLE || position == MessageGroupPosition.FIRST) ConnectedCorner else NormalCorner
        return RoundedCornerShape(
            topStart = if (outgoing) NormalCorner else top,
            topEnd = if (outgoing) top else NormalCorner,
            bottomStart = if (outgoing) NormalCorner else bottom,
            bottomEnd = if (outgoing) bottom else NormalCorner,
        )
    }
}
