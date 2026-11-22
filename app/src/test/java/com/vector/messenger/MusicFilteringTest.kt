package com.vector.messenger

import com.vector.core.music.*
import com.vector.messenger.feature.music.*
import com.vector.messenger.feature.search.MusicLibrary
import org.junit.Assert.*
import org.junit.Test

class MusicFilteringTest {
    private val first = MusicTrack(1, "First", "Alpha", "A", "content://media/1", 10, duration = 20000, folder = "Music/A")
    private val second = MusicTrack(2, "Second", "Beta", "B", "content://media/2", 20, duration = 10000, folder = "Music/B")
    private val library = MusicLibrary(listOf(first, second), listOf(MusicPlaylist("p", "Local fixture", listOf(second.key, "missing", first.key))), true, recent = listOf(second.key, first.key))
    @Test fun playlistsSkipMissingTracksButPreserveSavedOrder() { assertEquals(listOf(second, first), musicResults(library, MusicFilter("Playlists", "p"))) }
    @Test fun recentAndNewestOrderUseActualState() {
        assertEquals(listOf(second, first), musicResults(library, MusicFilter("Recently played")))
        assertEquals(listOf(second, first), musicResults(library, MusicFilter("Recently added")))
    }
    @Test fun albumArtistFolderAndSearchFiltersUseOneCatalogue() {
        assertEquals(listOf(first), musicResults(library, MusicFilter("Albums", "A")))
        assertEquals(listOf(second), musicResults(library, MusicFilter("Artists", "Beta")))
        assertEquals(listOf(first), musicResults(library, MusicFilter("Folders", "Music/A")))
        assertEquals(listOf(second), musicResults(library, MusicFilter("Songs", query = "Beta B")))
    }
    @Test fun collectionsIncludeLocalPlaylistsAndNoFabricatedAlbums() {
        assertEquals(listOf("p" to "Local fixture"), musicGroups(library, MusicFilter("Playlists", query = "fixture")))
        assertTrue(musicGroups(MusicLibrary(), MusicFilter("Albums")).isEmpty())
    }
}
