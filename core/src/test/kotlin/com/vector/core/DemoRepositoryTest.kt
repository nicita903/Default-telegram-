package com.vector.core

import com.vector.core.demo.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class DemoRepositoryTest {
    @Test fun foldersDistinguishGroupsAndChannels() {
        val chats = DemoChatRepository().chats.value
        assertEquals(listOf(3L), filterChats(chats, "", ChatFilter.Groups).map { it.id })
        assertEquals(listOf(4L), filterChats(chats, "", ChatFilter.Channels).map { it.id })
    }
    @Test fun localRepliesReactionsAndAttachmentsRemainScopedToTheirChat() = runTest {
        val repository = DemoChatRepository()
        repository.send(1, "Reply", replyTo = 10)
        val reply = repository.messages.value.last()
        assertEquals(10L, reply.replyTo)
        repository.toggleReaction(2, reply.id, "👍")
        assertNull(repository.messages.value.last().reaction)
        repository.toggleReaction(1, reply.id, "👍")
        assertEquals("👍", repository.messages.value.last().reaction)
        repository.toggleReaction(1, reply.id, "👍")
        assertNull(repository.messages.value.last().reaction)
        repository.send(1, "", replyTo = 20, attachment = Attachment("Notes.txt", "content://demo/notes", "text/plain"))
        assertNull(repository.messages.value.last().replyTo)
        assertEquals("Notes.txt", repository.messages.value.last().attachment?.name)
        assertEquals("Notes.txt", repository.chats.value.first { it.id == 1L }.preview)
    }
    @Test fun sendingUpdatesOnlyTheSelectedConversation() = runTest {
        val repository = DemoChatRepository()
        val untouched = repository.messages.value.filter { it.chatId == 2L }
        assertEquals(SendResult.Sent, repository.send(1, "  Hello  "))
        assertEquals("Hello", repository.messages.value.last().text)
        assertEquals(Delivery.LocalDemo, repository.messages.value.last().delivery)
        assertEquals("Hello", repository.chats.value.first { it.id == 1L }.preview)
        assertEquals(untouched, repository.messages.value.filter { it.chatId == 2L })
    }
    @Test fun invalidMessagesDoNotMutateState() = runTest {
        val repository = DemoChatRepository()
        val initial = repository.messages.value
        assertEquals(SendResult.Empty, repository.send(1, " \n "))
        assertEquals(SendResult.TooLong, repository.send(1, "a".repeat(4097)))
        assertEquals(SendResult.MissingChat, repository.send(999, "hello"))
        assertEquals(initial, repository.messages.value)
    }
    @Test fun demoHistoryIsBoundedAndIdsRemainUnique() = runTest {
        val repository = DemoChatRepository()
        repeat(150) { repository.send(1, "Message $it") }
        assertEquals(100, repository.messages.value.count { it.chatId == 1L })
        assertEquals(repository.messages.value.size, repository.messages.value.map { it.id }.distinct().size)
    }
    @Test fun readArchiveAndSearchWorkTogether() {
        val repository = DemoChatRepository()
        repository.markRead(1)
        assertFalse(filterChats(repository.chats.value, "alex", ChatFilter.Unread).any())
        repository.toggleArchive(1)
        assertTrue(filterChats(repository.chats.value, " ALEX ", ChatFilter.All).isEmpty())
        assertEquals(1L, filterChats(repository.chats.value, " ALEX ", ChatFilter.Archived).single().id)
        repository.toggleArchive(1)
        repository.togglePin(2)
        assertTrue(filterChats(repository.chats.value, "", ChatFilter.All).take(2).all { it.pinned })
    }
    @Test fun perContactGhostPreferencesAreIsolated() {
        val repository = DemoGhostRepository()
        repository.update(1, GhostPreferences(suppressTyping = true, hideLastSeen = true))
        repository.update(2, GhostPreferences(suppressRecording = true))
        assertTrue(repository.preferences.value.getValue(1).suppressTyping)
        assertFalse(repository.preferences.value.getValue(2).suppressTyping)
        assertTrue(repository.preferences.value.getValue(2).suppressRecording)
        assertTrue(DemoGhostRepository().preferences.value.isEmpty())
    }
}
