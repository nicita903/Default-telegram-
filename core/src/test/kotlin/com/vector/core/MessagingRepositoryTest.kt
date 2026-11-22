package com.vector.core

import com.vector.core.auth.*
import com.vector.core.demo.DemoChatRepository
import com.vector.core.messaging.*
import com.vector.core.profile.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MessagingRepositoryTest {
    private class Auth : AuthenticationRepository {
        override val state = MutableStateFlow(AuthState(AuthStep.Phone))
        override fun start() = Unit
        override suspend fun submit(action: AuthAction) = Unit
        fun ready(value: Boolean) { state.value = AuthState(if (value) AuthStep.Ready else AuthStep.LoggingOut) }
    }
    private class Client : TelegramMessagingClient {
        val events = Channel<ChatUpdate>(Channel.UNLIMITED)
        override val updates = events.receiveAsFlow()
        override val connection = MutableStateFlow(ProfileConnection.Ready)
        val calls = mutableListOf<String>()
        val pages = mutableMapOf<Long, List<Message>>()
        var historyGate: CompletableDeferred<Unit>? = null
        var sendGate: CompletableDeferred<Unit>? = null
        var failure = false
        var sent = message(1000, Delivery.Sending)
        override suspend fun loadChats(archived: Boolean, limit: Int): Boolean { calls += "load:$archived"; return false }
        override suspend fun history(chatId: Long, fromId: Long, limit: Int): List<Message> {
            calls += "history:$chatId:$fromId:$limit"
            val page = pages[fromId].orEmpty().map { it.copy(chatId = chatId) }
            historyGate?.await(); if (failure) throw MessagingException(MessagingFailure.Network)
            return page
        }
        override suspend fun sendText(chatId: Long, text: String, replyTo: Long?): Message {
            calls += "send"; val result = sent.copy(chatId = chatId); sendGate?.await()
            if (failure) throw MessagingException(MessagingFailure.Network)
            return result
        }
        override suspend fun sendVoice(chatId: Long, clip: com.vector.core.audio.VoiceClip, replyTo: Long?): Message {
            calls += "voice"
            sendGate?.await()
            if (failure) throw MessagingException(MessagingFailure.Network)
            return sent.copy(chatId = chatId)
        }
        override suspend fun resend(chatId: Long, messageId: Long): List<Message> { calls += "retry"; return listOf(sent.copy(chatId = chatId)) }
        override suspend fun openChat(chatId: Long) { calls += "open:$chatId" }
        override suspend fun closeChat(chatId: Long) { calls += "close:$chatId" }
        override suspend fun view(chatId: Long, ids: List<Long>) { calls += "view:${ids.size}" }
        override suspend fun pin(chatId: Long, archived: Boolean, pinned: Boolean) { calls += "pin:$pinned" }
        override suspend fun archive(chatId: Long, archived: Boolean) { calls += "archive:$archived" }
        override suspend fun downloadAvatar(fileId: Int) = ProfilePhoto(fileId)
    }
    private fun chat(id: Long = -100, order: Long = 10, archived: Boolean = false, pinned: Boolean = false) =
        TelegramChat(Chat(id, "Synthetic", "Preview", "12:00", unread = 5), listOf(ChatPosition(archived, order, pinned)))
    private suspend fun Client.event(update: ChatUpdate) { events.send(update) }
    @Test fun voiceSubmissionUsesNativePendingAndDoesNotInventDeliveryOrCrossSessions() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat())); runCurrent(); repo.openConversation(-100); runCurrent()
        val clip = com.vector.core.audio.VoiceClip("/fixture/voice.ogg", 2, listOf(0, 31))
        assertEquals(SendResult.Sent, repo.sendVoice(-100, clip, null))
        assertEquals(Delivery.Sending, repo.messages.value.single().delivery)
        assertEquals(1, client.calls.count { it == "voice" })
        client.failure = true
        assertEquals(SendResult.Uncertain, repo.sendVoice(-100, clip, null))
        auth.ready(false); runCurrent()
        assertEquals(SendResult.Unsupported, repo.sendVoice(-100, clip, null))
        assertTrue(repo.messages.value.none { it.chatId == -100L })
    }
    @Test fun signedInSourceNeverMergesDemoAndStartupEventsAreRetained() = runTest {
        val auth = Auth(); val client = Client()
        client.event(ChatUpdate.Snapshot(chat()))
        val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        runCurrent(); assertFalse(repo.status.value.live); assertTrue(repo.chats.value.any { it.id == 1L })
        auth.ready(true); runCurrent()
        assertTrue(repo.status.value.live); assertEquals(listOf(-100L), repo.chats.value.map { it.id })
        assertEquals(2, client.calls.count { it.startsWith("load:") })
        auth.ready(false); runCurrent()
        assertFalse(repo.status.value.live); assertFalse(repo.chats.value.any { it.id == -100L }); assertTrue(repo.messages.value.all { it.chatId > 0 })
    }
    @Test fun positionsDriveOrderPinArchiveRemovalAndUnreadIsServerOwned() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent()
        client.event(ChatUpdate.Snapshot(chat(-100, 900))); client.event(ChatUpdate.Snapshot(chat(-200, 800))); runCurrent()
        assertEquals(listOf(-100L, -200L), repo.chats.value.map { it.id })
        repo.markRead(-100); assertEquals(5, repo.chats.value.first().unread)
        client.event(ChatUpdate.Unread(-100, 2)); client.event(ChatUpdate.Position(-200, ChatPosition(false, 1000, true))); runCurrent()
        assertEquals(-200L, repo.chats.value.first().id); assertTrue(repo.chats.value.first().pinned)
        assertEquals(2, repo.chats.value.last().unread)
        repo.toggleArchive(-100); runCurrent(); assertFalse(repo.chats.value.last().archived)
        client.event(ChatUpdate.Position(-100, ChatPosition(false, 0, false))); client.event(ChatUpdate.Position(-100, ChatPosition(true, 20, false))); runCurrent()
        assertTrue(repo.chats.value.find { it.id == -100L }!!.archived)
        client.event(ChatUpdate.Position(-100, ChatPosition(true, 0, false))); runCurrent()
        assertEquals(listOf(-200L), repo.chats.value.map { it.id })
    }
    @Test fun historyPagesDeduplicateCursorAndAreBoundedWithoutTreatingShortPagesAsEnd() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        client.pages[0] = (451L..500L).map { message(it) }
        auth.ready(true); runCurrent(); repo.openConversation(-100); runCurrent()
        assertEquals(50, repo.messages.value.size); assertTrue(repo.history.value.hasMore)
        repeat(9) {
            val from = repo.messages.value.first().id
            client.pages[from] = ((from - 49).coerceAtLeast(1)..from).map { message(it) }
            repo.loadOlder(-100); runCurrent()
            assertTrue(repo.messages.value.size <= SessionChatRepository.WINDOW)
            assertEquals(repo.messages.value.size, repo.messages.value.map { it.id }.distinct().size)
        }
        val from = repo.messages.value.first().id
        client.pages[from] = listOf(message(from - 1), message(from)) // A short page with progress isn't end.
        repo.loadOlder(-100); runCurrent(); assertTrue(repo.history.value.hasMore)
        val last = repo.messages.value.first().id
        client.pages[last] = listOf(message(last)); repo.loadOlder(-100); runCurrent(); assertFalse(repo.history.value.hasMore)
        assertTrue(client.calls.filter { it.startsWith("history:") }.all { it.endsWith(":50") })
        client.event(ChatUpdate.Upsert(message(9000))); runCurrent()
        assertTrue(repo.messages.value.none { it.id == 9000L }) // No artificial gap while reading an evicted older window.
        client.pages[0] = listOf(message(9000)); repo.latest(-100); runCurrent()
        assertEquals(listOf(9000L), repo.messages.value.map { it.id })
    }
    @Test fun contentAndDeletionUpdatesWinOverInflightHistory() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent()
        client.pages[0] = listOf(message(10), message(11)); client.historyGate = CompletableDeferred()
        repo.openConversation(-100); runCurrent()
        client.event(ChatUpdate.Upsert(message(10).copy(text = "Edited fixture")))
        client.event(ChatUpdate.Deleted(-100, listOf(11))); runCurrent()
        client.historyGate!!.complete(Unit); runCurrent()
        assertEquals(listOf(10L), repo.messages.value.map { it.id }); assertEquals("Edited fixture", repo.messages.value.single().text)
    }
    @Test fun sendsUseTdlibStatesAndSucceededReplacementCannotResurrectTemporaryMessages() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat())); runCurrent(); repo.openConversation(-100); runCurrent()
        client.sendGate = CompletableDeferred()
        val result = async { repo.send(-100, "Synthetic text") }; runCurrent()
        client.event(ChatUpdate.Upsert(message(1001, Delivery.Sent), oldId = 1000)); runCurrent()
        client.sendGate!!.complete(Unit); runCurrent(); assertEquals(SendResult.Sent, result.await())
        assertEquals(listOf(1001L), repo.messages.value.map { it.id }); assertEquals(Delivery.Sent, repo.messages.value.single().delivery)
        client.event(ChatUpdate.ReadOutbox(-100, 1001)); runCurrent(); assertEquals(Delivery.Read, repo.messages.value.single().delivery)
    }
    @Test fun sameIdFailureStaysVisibleAndRetryRespectsTdlibPermissionsAndDelay() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); repo.openConversation(-100); runCurrent()
        client.event(ChatUpdate.Upsert(message(10, Delivery.Failed).copy(canRetry = true, retryAt = Long.MAX_VALUE), oldId = 10)); runCurrent()
        assertEquals(Delivery.Failed, repo.messages.value.single().delivery)
        repo.retry(-100, 10); runCurrent(); assertFalse("retry" in client.calls)
        client.event(ChatUpdate.Upsert(message(10, Delivery.Failed).copy(canRetry = true))); runCurrent()
        repo.retry(-100, 10); runCurrent(); assertTrue("retry" in client.calls)
    }
    @Test fun logoutInvalidatesPendingSendsAndClearsRealHistory() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat())); runCurrent(); repo.openConversation(-100); runCurrent()
        client.sendGate = CompletableDeferred(); val result = async { repo.send(-100, "Synthetic") }; runCurrent()
        val session = repo.status.value.session
        auth.ready(false); runCurrent(); client.sendGate!!.complete(Unit); runCurrent()
        assertEquals(SendResult.MissingChat, result.await()); assertTrue(repo.status.value.session > session)
        assertTrue(repo.messages.value.none { it.chatId == -100L })
    }
    @Test fun validationAndUnknownSendOutcomeNeverCreateDeliveredMessages() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat())); runCurrent()
        assertEquals(SendResult.Empty, repo.send(-100, " "))
        assertEquals(SendResult.TooLong, repo.send(-100, "x".repeat(4097)))
        assertEquals(SendResult.Unsupported, repo.send(-100, "file", attachment = Attachment("Fixture", "local", "text/plain")))
        client.failure = true
        assertEquals(SendResult.Uncertain, repo.send(-100, "Synthetic")); assertTrue(repo.messages.value.isEmpty())
    }
    @Test fun visibleReadsAreDeduplicatedAndRestrictionChangesHideExistingAndFutureContent() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat().copy(peerId = 42, supergroup = true))); runCurrent()
        repo.openConversation(-100); runCurrent(); client.event(ChatUpdate.Upsert(message(10))); runCurrent()
        repo.viewMessages(-100, listOf(10)); repo.viewMessages(-100, listOf(10)); runCurrent()
        assertEquals(1, client.calls.count { it.startsWith("view:") })
        client.event(ChatUpdate.Access(42, true, true)); runCurrent()
        assertTrue(repo.messages.value.isEmpty()); assertEquals("Content unavailable", repo.chats.value.single().preview)
        client.event(ChatUpdate.Upsert(message(11))); runCurrent(); assertTrue(repo.messages.value.isEmpty())
        assertEquals(SendResult.Forbidden, repo.send(-100, "Synthetic"))
    }
    @Test fun openingHistoryAndClosingDoNotReportReadsUntilMessagesAreActuallyVisible() = runTest {
        val auth = Auth(); val client = Client(); val repo = SessionChatRepository(auth, client, DemoChatRepository(), backgroundScope)
        auth.ready(true); runCurrent(); client.event(ChatUpdate.Snapshot(chat())); runCurrent()
        client.pages[0] = listOf(message(10))
        repo.markRead(-100); repo.openConversation(-100); runCurrent()
        assertTrue(client.calls.none { it.startsWith("view:") })
        assertEquals(5, repo.chats.value.single().unread)
        repo.viewMessages(-100, listOf(10)); runCurrent()
        assertEquals(1, client.calls.count { it.startsWith("view:") })
        assertEquals(5, repo.chats.value.single().unread) // A local callback cannot fabricate server read state.
        repo.closeConversation(-100); runCurrent(); repo.viewMessages(-100, listOf(10)); runCurrent()
        assertEquals(1, client.calls.count { it.startsWith("view:") })
        client.event(ChatUpdate.Unread(-100, 0)); runCurrent(); assertEquals(0, repo.chats.value.single().unread)
    }
    companion object {
        private fun message(id: Long, delivery: Delivery = Delivery.Received) = Message(id, -100, "Synthetic fixture", "12:00", delivery)
    }
}
