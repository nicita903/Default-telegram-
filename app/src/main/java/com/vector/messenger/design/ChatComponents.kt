package com.vector.messenger.design

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun ChatAvatar(name: String, size: Dp = 54.dp, id: Long = name.hashCode().toLong()) {
    val colors = DefaultDesign.colors
    val palette = listOf(colors.accent, colors.secondaryAccent, colors.important, colors.online)
    val tint = palette[((id xor (id ushr 32)) and Long.MAX_VALUE).rem(palette.size).toInt()]
    val initials = androidx.compose.runtime.remember(name) { avatarInitials(name) }
    Box(Modifier.size(size).background(tint.copy(alpha = .18f), DefaultDesign.avatarShape), contentAlignment = Alignment.Center) {
        Text(initials, color = tint, fontWeight = FontWeight.Medium, fontSize = (size.value * .32f).sp, maxLines = 1)
    }
}

private fun avatarInitials(name: String): String {
    fun letters(value: String, count: Int): String {
        val iterator = android.icu.text.BreakIterator.getCharacterInstance(java.util.Locale.ROOT).apply { setText(value) }
        var end = iterator.first()
        repeat(count) { val next = iterator.next(); if (next != android.icu.text.BreakIterator.DONE) end = next }
        return value.substring(0, end)
    }
    val words = name.trim().split(Regex("[\\p{Z}\\s]+")).filter { it.isNotEmpty() }
    return (if (words.size > 1) words.take(2).joinToString("") { letters(it, 1) } else words.firstOrNull()?.let { letters(it, 2) }.orEmpty())
        .uppercase(java.util.Locale.ROOT).ifBlank { "?" }
}
