package com.vector.messenger.design

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vector.core.profile.ProfilePhoto
import com.vector.messenger.feature.avatar.*
import com.vector.messenger.feature.media.MediaImages
import com.vector.messenger.feature.media.DecodedMediaImage
import com.vector.messenger.navigation.gesture.excludeModuleGestures
import com.vector.messenger.telegram.AvatarOwner
import kotlinx.coroutines.flow.flowOf

/** One session-aware, sampled image cache for every avatar and media thumbnail. */
@Composable
fun TelegramAvatar(name: String, photo: ProfilePhoto?, size: Dp, id: Long = 0, user: Boolean = false) {
    val avatars = LocalAvatarViewModel.current
    val owner = remember(id, user) { AvatarOwner(id, user) }
    key(owner, avatars, avatars?.state?.value?.session) {
    val source = remember(avatars, owner, name, photo) {
        if (avatars != null && id != 0L) avatars.observe(owner, name, photo) else flowOf(AvatarDisplay(0, name, photo))
    }
    val display by source.collectAsStateWithLifecycle(initialValue = AvatarDisplay(avatars?.state?.value?.session ?: 0, name, null))
    LaunchedEffect(avatars, owner, display.session, photo) { if (id != 0L) avatars?.ensure(owner, name, photo) }
    key(display.session, owner, display.photo?.fileId, display.photo?.localPath) {
        val path = display.photo?.localPath
        val bitmap by produceState<DecodedMediaImage?>(null, path) {
            value = path?.let { MediaImages.load(it, 256, display.session) }
            if (path != null && value == null) display.photo?.let { avatars?.missingFile(it.fileId) }
            else if (value != null) display.photo?.let { avatars?.decoded(it.fileId) }
        }
        val interactive = avatars != null && id != 0L && avatars.state.value.authenticated
        Box(Modifier.size(size).clip(DefaultDesign.avatarShape)
            .then(if (interactive) Modifier.excludeModuleGestures().clickable(onClickLabel = "View ${display.name} profile photo") {
                avatars?.open(owner, display.name, display.photo)
            } else Modifier)) {
            if (bitmap == null) ChatAvatar(display.name, size, if (id != 0L) id else name.hashCode().toLong())
            else Image(bitmap!!.bitmap.asImageBitmap(), "${display.name} profile picture", Modifier.size(size), contentScale = ContentScale.Crop)
        }
    }
    }
}
