package com.vector.messenger

import androidx.lifecycle.SavedStateHandle
import com.vector.core.*
import com.vector.core.demo.DemoChatRepository
import com.vector.core.demo.DemoGhostRepository
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.PreferencesViewModel
import com.vector.messenger.navigation.Routes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import com.vector.messenger.feature.AppearancePreferences
import com.vector.messenger.feature.AppearanceSettings
import kotlinx.coroutines.flow.MutableStateFlow

@OptIn(ExperimentalCoroutinesApi::class)
class ChatsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun viewModelFiltersRepositoryUpdates() = runTest(dispatcher) {
        val repository = DemoChatRepository()
        val viewModel = ChatsViewModel(repository)
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.filteredChats.collect() }
        viewModel.search("mira")
        advanceUntilIdle()
        assertEquals(2L, viewModel.filteredChats.value.single().id)
        viewModel.filter(ChatFilter.Unread)
        viewModel.open(2)
        advanceUntilIdle()
        assertTrue(viewModel.filteredChats.value.isEmpty())
    }
    @Test fun sendClearsDraftOnlyOnSuccess() = runTest(dispatcher) {
        val viewModel = ChatsViewModel(DemoChatRepository())
        var cleared = false
        viewModel.send(1, " ") { cleared = true }
        advanceUntilIdle()
        assertFalse(cleared)
        assertNotNull(viewModel.error.value)
        viewModel.send(1, "Hello") { cleared = true }
        advanceUntilIdle()
        assertTrue(cleared)
        assertNull(viewModel.error.value)
    }
    @Test fun settingsRestoreThroughSavedState() {
        val state = SavedStateHandle()
        val preferences = object : AppearancePreferences {
            override val state = MutableStateFlow(AppearanceSettings())
            override fun update(change: (AppearanceSettings) -> AppearanceSettings) { this.state.value = change(this.state.value) }
        }
        val first = PreferencesViewModel(DemoGhostRepository(), state, preferences)
        first.hidePreviews(false)
        val restored = PreferencesViewModel(DemoGhostRepository(), state, preferences)
        assertFalse(restored.hidePreviews.value)
    }
    @Test fun conversationRoutesRequireValidIdentifiers() {
        assertEquals("conversation/42", Routes.conversation(42))
        assertEquals("conversation/-100", Routes.conversation(-100))
        assertThrows(IllegalArgumentException::class.java) { Routes.conversation(0) }
    }
}
