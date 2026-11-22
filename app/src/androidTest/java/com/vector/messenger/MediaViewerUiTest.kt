package com.vector.messenger

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.auth.*
import com.vector.core.media.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.media.*
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*

class MediaViewerUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scope: CoroutineScope
    private lateinit var vm: MediaViewModel
    private val store = ViewModelStore()
    private lateinit var fixture: File
    private lateinit var items: List<MediaItem>
    private val extraFixtures = mutableListOf<File>()
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Ready))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
    }
    private class Files : TelegramFileClient {
        override val updates = MutableSharedFlow<MediaEvent>()
        override suspend fun downloads(offset: String) = DownloadPage(emptyList(), "")
        override suspend fun start(item: MediaItem, managed: Boolean, priority: Int) = item.media.file
        override suspend fun pause(id: Int, managed: Boolean) = Unit
        override suspend fun remove(id: Int) = Unit
        override suspend fun validate(file: MediaFile) = file
        override suspend fun exportFile(item: MediaItem) = null // UI fixtures never export or contact Telegram.
    }
    @Before fun before() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "telegram/files").apply { mkdirs() }
        fixture = File(directory, "media-ui-${UUID.randomUUID()}.png")
        val bitmap = Bitmap.createBitmap(1200, 900, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(38, 100, 200)) }
        fixture.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        items = listOf(1, 2).map { id -> MediaItem(-100, id.toLong(), TelegramMedia(MediaKind.Photo,
            MediaFile(id, fixture.length(), fixture.length(), complete = true, path = fixture.path),
            if (id == 1) "First fixture" else "Second fixture", "image/png", width = 1200, height = 900), false) }
        compose.runOnUiThread {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            vm = MediaViewModel(MediaRepository(Auth(), Files(), scope)); store.put("media", vm)
        }
    }
    @After fun after() {
        compose.runOnUiThread { store.clear(); scope.cancel() }
        fixture.delete()
        extraFixtures.forEach { it.delete() }
    }
    private fun open() {
        compose.setContent {
            var shown by remember { mutableStateOf(true) }
            DefaultTheme { if (shown) MediaViewer(items, 1, vm) { shown = false } }
        }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("media_viewer").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }
    @Test fun horizontalNavigationAndDownwardDismissalUseThePhotoSurface() {
        open()
        compose.onNodeWithTag("media_viewer").performTouchInput {
            swipe(Offset(width * .75f, height * .5f), Offset(width * .25f, height * .5f), 250)
        }
        compose.onNodeWithText("Second fixture").assertIsDisplayed()
        compose.onNodeWithTag("media_viewer").performTouchInput {
            swipe(Offset(width * .5f, height * .4f), Offset(width * .5f, height * .7f), 250)
        }
        compose.onNodeWithTag("media_viewer").assertDoesNotExist()
    }
    @Test fun doubleTapZoomPreventsDownwardDismissalAndCloseStillWorks() {
        open()
        compose.onNodeWithTag("media_viewer").performTouchInput { doubleClick(Offset(width * .5f, height * .5f)) }
        compose.onNodeWithText("3.0×").assertIsDisplayed()
        compose.onNodeWithTag("media_viewer").performTouchInput {
            swipe(Offset(width * .5f, height * .4f), Offset(width * .5f, height * .7f), 250)
        }
        compose.onNodeWithTag("media_viewer").assertIsDisplayed()
        // Tap reveals controls if the first tap of the double-tap hid them.
        if (compose.onAllNodesWithContentDescription("Close media").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("media_viewer").performTouchInput { click(Offset(width * .5f, height * .5f)) }
        compose.onNodeWithContentDescription("Close media").performClick()
        compose.onNodeWithTag("media_viewer").assertDoesNotExist()
    }
    @Test fun androidBackClosesZoomedViewer() {
        open()
        compose.onNodeWithTag("media_viewer").performTouchInput { doubleClick(Offset(width * .5f, height * .5f)) }
        compose.onNodeWithText("3.0×").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.waitForIdle(); compose.onNodeWithTag("media_viewer").assertDoesNotExist()
    }
    @Test fun pinchZoomTakesPriorityOverPagingAndDismissal() {
        open()
        compose.onNodeWithTag("media_viewer").performTouchInput {
            pinch(Offset(width * .35f, height * .5f), Offset(width * .15f, height * .5f),
                Offset(width * .65f, height * .5f), Offset(width * .85f, height * .5f), 400)
        }
        compose.onAllNodesWithText("×", substring = true).assertCountEquals(1)
        compose.onNodeWithTag("media_viewer").performTouchInput {
            swipe(Offset(width * .5f, height * .4f), Offset(width * .5f, height * .7f), 250)
        }
        compose.onNodeWithTag("media_viewer").assertIsDisplayed()
        compose.onNodeWithText("First fixture").assertIsDisplayed()
    }
    @Test fun actualDeviceVideoDecoderSupportsPlayPauseMuteAndSeekControls() {
        val video = File(fixture.parentFile, "media-ui-${UUID.randomUUID()}.mp4").also { extraFixtures += it }
        writeSyntheticVideo(video)
        compose.setContent { DefaultTheme { VideoMedia(video.path, 3, false) } }
        compose.onNodeWithText("Play").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Pause").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Mute").performClick(); compose.onNodeWithText("Unmute").assertIsDisplayed()
        compose.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
            .performTouchInput { click(Offset(width * .6f, height * .5f)) }
        compose.onNodeWithText("Pause").performClick(); compose.onNodeWithText("Play").assertIsDisplayed()
    }
    @Test fun animatedGifIsExplicitlyStartedAndPaused() {
        val gif = File(fixture.parentFile, "media-ui-${UUID.randomUUID()}.gif").also { extraFixtures += it }
        writeSyntheticGif(gif)
        compose.setContent { DefaultTheme { GifMedia(gif.path) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("Play animation").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play animation").assertIsEnabled().performClick()
        compose.onNodeWithText("Pause animation").performClick()
        compose.onNodeWithText("Play animation").assertIsDisplayed()
    }
}
