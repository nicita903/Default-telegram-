package com.vector.messenger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.vector.core.*
import com.vector.core.auth.*
import com.vector.core.demo.DemoChatRepository
import com.vector.core.messaging.*
import com.vector.core.profile.*
import com.vector.messenger.design.DefaultTheme
import com.vector.messenger.feature.ChatsScreen
import com.vector.messenger.feature.ChatsViewModel
import com.vector.messenger.feature.ConversationScreen
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.junit.*

/** Entire transport is fake: no authentication, JNI or real message delivery. */
class RealMessagingUiTest {
    @get:Rule val compose = createComposeRule()
    private lateinit var scope: CoroutineScope
    private lateinit var vm: ChatsViewModel
    private lateinit var client: Client
    private lateinit var auth: Auth
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Ready))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
    }
    private class Client : TelegramMessagingClient {
        val events = Channel<ChatUpdate>(Channel.UNLIMITED)
        override val updates = events.receiveAsFlow()
        override val connection = MutableStateFlow(ProfileConnection.Ready)
        var sendCount = 0; var retryCount = 0
        var sendGate: CompletableDeferred<Unit>? = null
        override suspend fun loadChats(archived: Boolean, limit: Int) = false
        override suspend fun history(chatId: Long, fromId: Long, limit: Int) = if (fromId == 0L) listOf(Message(10, chatId, "Remote fixture", "12:00", Delivery.Received)) else emptyList()
        override suspend fun sendText(chatId: Long, text: String, replyTo: Long?): Message {
            sendCount++; sendGate?.await(); return Message(20, chatId, text, "12:01", Delivery.Sending)
        }
        override suspend fun resend(chatId: Long, messageId: Long): List<Message> { retryCount++; return listOf(Message(31, chatId, "Retried fixture", "12:02", Delivery.Sending)) }
        override suspend fun openChat(chatId: Long) = Unit
        override suspend fun closeChat(chatId: Long) = Unit
        override suspend fun view(chatId: Long, ids: List<Long>) = Unit
        override suspend fun pin(chatId: Long, archived: Boolean, pinned: Boolean) = Unit
        override suspend fun archive(chatId: Long, archived: Boolean) = Unit
        override suspend fun downloadAvatar(fileId: Int) = ProfilePhoto(fileId)
    }
    @Before fun before() {
        compose.runOnUiThread {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            client = Client(); auth = Auth()
            client.events.trySend(ChatUpdate.Snapshot(TelegramChat(Chat(-100, "Telegram fixture", "Remote fixture", "12:00"), listOf(ChatPosition(false, 100, false)))))
            vm = ChatsViewModel(SessionChatRepository(auth, client, DemoChatRepository(), scope))
        }
    }
    @After fun after() { if (::scope.isInitialized) compose.runOnUiThread { scope.cancel() } }
    private fun event(update: ChatUpdate) { compose.runOnUiThread { client.events.trySend(update) }; compose.waitForIdle() }
    @Test fun liveChatListContainsNoDemoAndLogoutRestoresAnExplicitDemoSource() {
        compose.setContent { DefaultTheme(ThemeMode.Dark) { ChatsScreen(vm, {}) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Telegram fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Alex Morgan").assertDoesNotExist()
        compose.onNodeWithText("New chat").assertIsDisplayed()
        compose.runOnUiThread { auth.state.value = AuthState(AuthStep.LoggingOut) }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Alex Morgan").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Telegram fixture").assertDoesNotExist()
    }
    @Test fun aDelayedSendResponseDoesNotEraseNewlyTypedText() {
        compose.runOnUiThread { client.sendGate = CompletableDeferred() }
        compose.setContent { DefaultTheme(ThemeMode.Dark) { ConversationScreen(-100, vm) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Remote fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_composer").performTextInput("First fixture")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithTag("message_composer").performTextInput(" retained")
        compose.runOnUiThread { client.sendGate!!.complete(Unit) }
        compose.waitForIdle()
        compose.onNodeWithTag("message_composer").assertTextEquals("First fixture retained")
        compose.onNodeWithContentDescription("Sending message").assertIsDisplayed()
    }
    @Test fun textSendAndNativeDeliveryUpdatesDriveBubbleStateAndFailureRetry() {
        compose.setContent { DefaultTheme(ThemeMode.Dark) { ConversationScreen(-100, vm) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Remote fixture").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("message_composer").performTextInput("Synthetic send")
        compose.onNodeWithContentDescription("Send message").performClick()
        compose.onNodeWithContentDescription("Sending message").assertIsDisplayed()
        compose.runOnIdle { Assert.assertEquals(1, client.sendCount) }
        event(ChatUpdate.Upsert(Message(21, -100, "Synthetic send", "12:01", Delivery.Sent), 20))
        compose.onNodeWithContentDescription("Sent to Telegram").assertIsDisplayed()
        event(ChatUpdate.ReadOutbox(-100, 21))
        compose.onNodeWithContentDescription("Read").assertIsDisplayed()
        event(ChatUpdate.Upsert(Message(30, -100, "Failed fixture", "12:02", Delivery.Failed, canRetry = true), 30))
        compose.onNodeWithText("Failed fixture").performTouchInput { longClick() }
        compose.onNodeWithText("Retry message").performClick()
        compose.runOnIdle { Assert.assertEquals(1, client.retryCount) }
    }
    @Test fun protectedMessagesDisableTextSelectionAndAuthChangeClearsTheComposer() {
        compose.setContent { DefaultTheme(ThemeMode.Dark) { ConversationScreen(-100, vm) } }
        compose.waitUntil(5000) { compose.onAllNodesWithText("Remote fixture").fetchSemanticsNodes().isNotEmpty() }
        event(ChatUpdate.Protection(-100, true))
        compose.onNodeWithText("Remote fixture").performTouchInput { longClick() }
        compose.onNodeWithText("Select text").assertDoesNotExist()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("message_composer").performTextInput("Private fixture draft")
        compose.runOnUiThread { auth.state.value = AuthState(AuthStep.LoggingOut) }
        compose.waitForIdle()
        compose.onNodeWithTag("message_composer").assertTextEquals("Demo message", "")
    }
}
