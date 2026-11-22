package com.vector.messenger.feature

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.design.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment

@Composable
fun MusicScreen(onSection: (String) -> Unit = {}) {
    com.vector.messenger.feature.music.MusicHome(onSection)
}

@Composable
fun MusicSectionScreen(section: String) {
    com.vector.messenger.feature.music.MusicLibrarySection(section)
}

@Composable
fun DownloadsScreen() {
    com.vector.messenger.feature.media.DownloadManagerScreen()
}
