package com.vector.messenger

import androidx.activity.compose.setContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.*
import com.vector.core.*
import com.vector.core.demo.DemoChatRepository
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.ChatsScreen
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.search.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*

class SearchScrollRestorationUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun before() = appearance.before()
    @After fun after() = appearance.after()
    @Test fun actualChatsListInstanceAndNonzeroScrollSurviveOverlayDismissal() {
        // Test-only long repository; the app's real/demo repository is never mutated.
        val repository = object : ChatRepository by DemoChatRepository() {
            override val chats = MutableStateFlow((1L..60L).map { Chat(it, "Fixture chat $it", "Fixture preview", "12:00") })
        }
        val open = mutableStateOf(false)
        lateinit var list: LazyListState
        compose.runOnUiThread {
            val provider = ViewModelProvider(compose.activity, object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = when (modelClass) {
                    ChatsViewModel::class.java -> ChatsViewModel(repository)
                    else -> SearchViewModel(SavedStateHandle(), repository, MusicLibraryRepository(compose.activity.applicationContext))
                } as T
            })
            val chats = provider.get("fixtureChats", ChatsViewModel::class.java)
            val search = provider.get("fixtureSearch", SearchViewModel::class.java)
            compose.activity.setContent {
                DefaultTheme(ThemeMode.Dark) {
                    list = rememberLazyListState()
                    val visible = remember { MutableTransitionState(false) }
                    LaunchedEffect(open.value) { visible.targetState = open.value }
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxSize().then(if (open.value || visible.currentState) Modifier.clearAndSetSemantics {} else Modifier)) {
                            ChatsScreen(chats, {}, { open.value = true }, list)
                        }
                        SearchOverlay(visible, open.value, search, SearchScope.Chats, 0, {}, { open.value = false }, {})
                    }
                }
            }
        }
        compose.onNodeWithTag("chat_list").performScrollToIndex(20)
        val original = list
        var position = 0 to 0
        compose.runOnIdle { position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset; Assert.assertTrue(position.first > 0); open.value = true }
        compose.onNodeWithText("Chat Search").assertIsDisplayed()
        val distance = with(compose.density) { 130.dp.toPx() }
        compose.onNodeWithTag("search_scope").performTouchInput { swipe(center, center + Offset(0f, distance), 350) }
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
        compose.runOnIdle {
            Assert.assertSame(original, list)
            Assert.assertEquals(position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
    }
}
