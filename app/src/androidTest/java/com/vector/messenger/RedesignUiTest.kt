package com.vector.messenger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import com.vector.messenger.feature.ChatsViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.After

class RedesignUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val appearance = TerminalTestAppearance(compose)
    @Before fun resetAppearance() = appearance.before()
    @After fun restoreAppearance() = appearance.after()

    @Test fun newMessageContactPickerAndClearSearchRemainFunctional() {
        openChatSearch(compose)
        compose.onNodeWithTag("search_input").performTextInput("Daniel")
        compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear search").performClick()
        compose.onNodeWithTag("search_results").performScrollToNode(hasText("Alex Morgan"))
        compose.onNodeWithText("Alex Morgan").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.onNodeWithContentDescription("New chat").performClick()
        compose.onNodeWithText("Choose a sample contact").assertIsDisplayed()
        compose.onNode(hasText("Mira Chen") and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithTag("message_composer").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back to chats").performClick()
        compose.onNodeWithTag("chat_list").assertIsDisplayed()
    }

    @Test fun customContextMenuPreservesPinAndArchiveActions() {
        try {
            compose.onNodeWithContentDescription("Actions for Daniel").performClick()
            compose.onNodeWithText("Pin").performClick()
            compose.onNodeWithContentDescription("Actions for Daniel").performClick()
            compose.onNodeWithText("Unpin").assertIsDisplayed().performClick()
            compose.onNodeWithContentDescription("Actions for Daniel").performClick()
            compose.onNodeWithText("Archive").performClick()
            compose.onNodeWithText("Daniel").assertDoesNotExist()
            compose.onNodeWithText("Archived").performScrollTo().performClick()
            compose.onNodeWithText("Daniel").assertIsDisplayed()
            compose.onNodeWithContentDescription("Actions for Daniel").performClick()
            compose.onNodeWithText("Unarchive").performClick()
            compose.onNodeWithText("All chats").performScrollTo().performClick()
            compose.onNodeWithText("Daniel").assertIsDisplayed()
        } finally {
            compose.runOnUiThread {
                val viewModel = ViewModelProvider(compose.activity)[ChatsViewModel::class.java]
                val daniel = viewModel.chats.value.first { it.id == 5L }
                if (daniel.archived) viewModel.archive(daniel.id)
                if (daniel.pinned) viewModel.pin(daniel.id)
            }
        }
    }
}
