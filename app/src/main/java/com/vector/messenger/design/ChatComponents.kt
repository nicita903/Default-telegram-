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

@Composable
fun ChatAvatar(name: String, size: Dp = 54.dp) {
    val colors = DefaultDesign.colors
    Box(Modifier.size(size).background(colors.accent.copy(alpha = .18f), DefaultDesign.avatarShape), contentAlignment = Alignment.Center) {
        Text(name.split(" ").take(2).joinToString("") { it.take(1) }.uppercase(), color = colors.active, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.titleMedium)
    }
}
