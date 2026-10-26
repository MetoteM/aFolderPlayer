package dev.alex.folderplayer

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryVisibilityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("library", Context.MODE_PRIVATE)
    private val items = listOf("a", "b", "c").map { Track(it, it, "Artist", 30000, "Music") }

    @Before fun prepare() {
        prefs.edit().clear().putString("tracks", JSONArray(items.map { it.json() }).toString()).commit()
    }

    @Test fun hiddenTracksStayVisibleButAreExcludedFromPlaybackAndCanReturn() {
        val library = Library(context)
        library.setTracksHidden(setOf("a", "b"), true)
        assertEquals(3, library.tracks().size)
        assertEquals(listOf("c"), Library(context).playableTracks().map { it.uri })
        library.setTracksHidden(setOf("a", "b"), false)
        assertEquals(items, Library(context).playableTracks())
    }

    @Test fun exclusionSurvivesReloadOfScannedMetadataAndDoesNotDeletePlaylistMembership() {
        val library = Library(context)
        library.savePlaylist("P", listOf("a", "b"))
        library.excludeTracks(setOf("a"))
        prefs.edit().putString("tracks", JSONArray(items.map { it.json() }).toString()).commit()
        assertEquals(listOf("b", "c"), Library(context).tracks().map { it.uri })
        assertEquals(setOf("a", "b"), library.playlist("P"))
        assertEquals(listOf("b"), library.ordered(library.playableTracks(), "P", 0).map { it.uri })
    }
}
