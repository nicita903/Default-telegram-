package com.vector.messenger

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.vector.core.demo.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.*
import com.vector.messenger.feature.search.*
import com.vector.messenger.navigation.DefaultApp
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import java.util.UUID

/** Isolated presentation fixture for the single Aurora theme: never uses a live TDLib client. */
class AuroraUiTest {
    @get:Rule val compose = createComposeRule()
    private val models = ViewModelStore()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var appearance: AppearanceStore
    private lateinit var preferences: PreferencesViewModel
    private lateinit var chats: ChatsViewModel
    private lateinit var search: SearchViewModel
    private var keyboard: SoftwareKeyboardController? = null
    private val prefix = "aurora-${UUID.randomUUID()}-"
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun setUp() {
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(prefix + name, mode)
        }
        compose.runOnUiThread {
            val fixture = DemoChatRepository()
            val repository = object : com.vector.core.ChatRepository by fixture {
                override val messages = fixture.messages.map { messages -> messages.map {
                    it.copy(date = 1794744000L + if (it.id >= 100) 86400L else 0L) // Chronological synthetic dates, test UI only.
                } }.stateIn(scope, SharingStarted.Eagerly, emptyList())
            }
            appearance = AppearanceStore(isolated)
            preferences = PreferencesViewModel(DemoGhostRepository(), SavedStateHandle(), appearance)
            chats = ChatsViewModel(repository)
            search = SearchViewModel(SavedStateHandle(), repository, MusicLibraryRepository(isolated, scope))
            models.put("preferences", preferences); models.put("chats", chats); models.put("search", search)
        }
        compose.setContent {
            val settings by appearance.state.collectAsStateWithLifecycle()
            CompositionLocalProvider(LocalAppearance provides settings) {
                DefaultTheme {
                    keyboard = LocalSoftwareKeyboardController.current
                    DefaultApp(chats, preferences, search, {})
                }
            }
        }
    }

    @After fun cleanUp() {
        compose.runOnUiThread { models.clear(); scope.cancel() }
        listOf("appearance_v2", "music_library_v1", "music_playback_v1").forEach { context.deleteSharedPreferences(prefix + it) }
    }

    /** The accent is the only appearance axis left that repaints the whole surface. */
    private fun accent(value: Int) {
        compose.runOnUiThread { appearance.update { it.copy(accent = value) } }
        compose.waitForIdle()
    }
    private fun module(name: String) {
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText(name).performClick()
        compose.waitForIdle()
    }
    private fun hideKeyboard() { compose.runOnUiThread { keyboard?.hide() }; compose.waitForIdle() }

    @Test fun auroraRendersEveryModuleAndConversation() {
        module("Chats"); saveDesignScreenshot(compose.onRoot(), "aurora-chats")
        compose.onNodeWithText("Alex Morgan").performClick()
        saveDesignScreenshot(compose.onRoot(), "aurora-conversation")
        for (name in listOf("Contacts", "Music", "Downloads", "Ghost Mode", "Settings")) {
            module(name)
            saveDesignScreenshot(compose.onRoot(), "aurora-${name.lowercase().replace(' ', '-')}")
        }
    }

    @Test fun moduleMenuCoversTheScreenAndReturnsToTheSameRoute() {
        module("Music")
        compose.onNodeWithText("Playlists").performClick()
        compose.onNodeWithContentDescription("Open navigation").performClick()
        compose.onNodeWithText("Modules").assertIsDisplayed()
        compose.onNodeWithText("Music").assertIsSelected()
        // Full-screen surface, not a dialog window: it must measure against the whole root.
        val rootHeight = compose.onRoot().fetchSemanticsNode().boundsInRoot.height
        val menuHeight = compose.onNodeWithTag("module_menu").fetchSemanticsNode().boundsInRoot.height
        Assert.assertTrue("menu height $menuHeight of root $rootHeight", menuHeight >= rootHeight * .9f)
        saveDesignScreenshot(compose.onRoot(), "aurora-menu")
        compose.onNodeWithContentDescription("Close navigation").performClick()
        compose.onNodeWithContentDescription("Back within module").assertIsDisplayed()
    }

    @Test fun conversationDraftAndActionsSurviveAccentChanges() {
        compose.onNodeWithText("Alex Morgan").performClick()
        saveDesignScreenshot(compose.onRoot(), "aurora-conversation-day")
        compose.onNodeWithTag("message_composer").performTextInput("Isolated draft")
        hideKeyboard()
        accent(2)
        compose.onNodeWithTag("message_composer").assertTextEquals("Isolated draft")
        compose.onNodeWithContentDescription("Attach file").assertIsDisplayed()
        compose.onNodeWithContentDescription("Send demo message").performClick()
        compose.onNodeWithText("Isolated draft").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "aurora-conversation-sent")
        compose.onNodeWithText("Isolated draft").performTouchInput { longClick() }
        saveDesignScreenshot(compose.onNode(isDialog()), "aurora-context")
        compose.onNodeWithText("Done").performClick()
        accent(0)
        compose.onNodeWithText("Isolated draft").assertIsDisplayed()
        Assert.assertEquals(1, chats.messages.value.count { it.text == "Isolated draft" })
    }

    @Test fun auroraRetainsHoldReleaseAndPullSearchGestureOwnership() {
        saveDesignScreenshot(compose.onRoot(), "aurora-chats-pull")
        val header = compose.onNodeWithTag("header_gesture_surface")
        header.performTouchInput { down(center); moveBy(Offset(with(compose.density) { 32.dp.toPx() }, 0f)) }
        compose.waitUntil(3000) { header.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Activated" }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        header.performTouchInput { up() }
        compose.onNodeWithContentDescription("Close navigation").assertIsDisplayed()
        saveDesignScreenshot(compose.onRoot(), "aurora-menu")
        compose.onNodeWithContentDescription("Close navigation").performClick()
        module("Contacts")
        header.performTouchInput { down(center); moveBy(Offset(-with(compose.density) { 32.dp.toPx() }, 0f)) }
        compose.waitUntil(3000) { header.fetchSemanticsNode().config.getOrElse(SemanticsProperties.StateDescription) { "" } == "Activated" }
        header.performTouchInput { up() }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        // An early release remains a cancellation in the full-screen menu layer.
        header.performTouchInput { down(center); moveBy(Offset(with(compose.density) { 32.dp.toPx() }, 0f)); up() }
        compose.onNodeWithContentDescription("Close navigation").assertDoesNotExist()
        compose.onNodeWithTag("chat_list").performTouchInput { swipe(Offset(center.x, 30f), Offset(center.x, 30f + with(compose.density) { 130.dp.toPx() }), 400) }
        compose.onNodeWithTag("search_scope").performTouchInput {
            swipe(center, center + Offset(with(compose.density) { 100.dp.toPx() }, 0f), 120)
        }
        compose.onNodeWithText("Music Search").assertIsDisplayed()
        compose.onNodeWithTag("search_scope").performTouchInput {
            swipe(center, center + Offset(0f, with(compose.density) { 140.dp.toPx() }), 300)
        }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        module("Contacts"); saveDesignScreenshot(compose.onRoot(), "aurora-contacts")
        module("Ghost Mode"); saveDesignScreenshot(compose.onRoot(), "aurora-ghost")
        module("Downloads"); saveDesignScreenshot(compose.onRoot(), "aurora-downloads")
        module("Music"); saveDesignScreenshot(compose.onRoot(), "aurora-music")
    }
}
