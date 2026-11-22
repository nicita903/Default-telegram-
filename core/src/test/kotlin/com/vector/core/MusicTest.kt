package com.vector.core

import com.vector.core.music.*
import org.junit.Assert.*
import org.junit.Test

class MusicTest {
    private fun track(id: Long, title: String = "Track $id", artist: String = "Artist", album: String = "Album") = MusicTrack(id, title, artist, album, "content://media/external/audio/media/$id", 0)
    @Test fun queueNormalizationPreservesSelectedIdentityAndBoundsInvalidInput() {
        val queue = MusicQueue(listOf("a", "b", "a", "c"), 2, -100, speed = 10f).normalized()
        assertEquals(listOf("a", "b", "c"), queue.keys); assertEquals(0, queue.index)
        assertEquals(0L, queue.position); assertEquals(1f, queue.speed)
        assertEquals(0, MusicQueue(emptyList(), 99).normalized().index)
        assertEquals(1000, MusicQueue((1..2000).map(Int::toString)).normalized().keys.size)
    }
    @Test fun queueReorderingPreservesCurrentTrackAndPlaybackOptions() {
        val queue = MusicQueue(listOf("a", "b", "c"), 1, 45000, true, MusicRepeat.All, 1.5f)
        val moved = queue.move(1, 0)
        assertEquals(listOf("b", "a", "c"), moved.keys); assertEquals(0, moved.index)
        assertEquals(45000L, moved.position); assertTrue(moved.shuffle); assertEquals(MusicRepeat.All, moved.repeat)
        assertEquals(queue, queue.move(-1, 0))
    }
    @Test fun playlistOrderAndReadOnlySystemListsAreRespected() {
        val list = MusicPlaylist("fixture", "Fixture", listOf("a", "b", "c"))
        assertEquals(listOf("b", "c", "a"), list.move(0, 2).keys)
        assertEquals(list.copy(readOnly = true), list.copy(readOnly = true).move(0, 2))
    }
    @Test fun offlineSearchMatchesWordsAcrossMetadataWithoutInventingResults() {
        val library = listOf(track(1, "Night", "Sample", "Blue"), track(2, "Morning", "Other", "Red"))
        assertEquals(listOf(library.first()), filterMusic(library, "  SAMPLE blue  "))
        assertTrue(filterMusic(library, "missing").isEmpty()); assertEquals(library, filterMusic(library, " "))
    }
    @Test fun supportedContainerMappingRejectsUnknownFiles() {
        listOf("mp3", "flac", "aac", "m4a", "ogg", "opus", "wav").forEach { assertNotNull(musicMime("", "fixture.$it")) }
        assertNull(musicMime("application/zip", "fixture.zip")); assertEquals("audio/mp4", musicMime("audio/x-m4a", "fixture"))
    }
    @Test fun fakePlayerIsPausedForVoiceAndQueueIsParkedWithoutAutomaticResume() {
        val fake = object : MusicInterruptionSource {
            override val index = 1; override val position = 12000L
            var paused = false
            override fun pause() { paused = true }
        }
        val original = MusicQueue(listOf("a", "b"), shuffle = true, repeat = MusicRepeat.One)
        val parked = parkMusic(original, fake)
        assertTrue(fake.paused); assertTrue(parked.requiresManualResume)
        assertEquals("b", parked.queue.keys[parked.queue.index]); assertEquals(12000L, parked.queue.position)
        assertTrue(parked.queue.shuffle); assertEquals(MusicRepeat.One, parked.queue.repeat)
    }
    @Test fun seededShuffleIsDeterministicAndIncludesEveryTrackStartingAtSelection() {
        val order = musicShuffleOrder(10, 3, 42)
        assertEquals(3, order.first()); assertEquals((0 until 10).toSet(), order.toSet())
        assertArrayEquals(order, musicShuffleOrder(10, 3, 42))
        assertEquals(0, musicShuffleOrder(0, 0, 42).size)
    }
    @Test fun restoringMissingCurrentTrackNeverAppliesItsPositionToAnotherSong() {
        val saved = MusicQueue(listOf("a", "missing", "b"), 1, 45000, true, MusicRepeat.All)
        val restored = restoreMusicQueue(saved, setOf("a", "b"))
        assertEquals(listOf("a", "b"), restored.keys); assertEquals(0L, restored.position)
        val same = restoreMusicQueue(saved.copy(index = 2), setOf("b"))
        assertEquals(listOf("b"), same.keys); assertEquals(45000L, same.position)
    }
}
