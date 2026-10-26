package dev.alex.folderplayer

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], manifest=Config.NONE)
class LibraryTrackStoreTest {
    private val prefs get() = RuntimeEnvironment.getApplication()
        .getSharedPreferences("library", Context.MODE_PRIVATE)

    @Test fun separateInstancesReuseDecodedObjectsAndLegacyWritesInvalidateCache() {
        prefs.edit().clear().putString("tracks", """[{"uri":"a","title":"A","artist":"X","duration":30000,"folder":""}]""").commit()
        val first = LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs).read()
        assertSame(first, LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs).read())
        assertEquals(first.single(), Library(RuntimeEnvironment.getApplication()).tracks().single())
        prefs.edit().putString("tracks", "[]").commit()
        assertTrue(LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs).read().isEmpty())
        assertEquals("a", first.single().uri)
    }

    @Test fun snapshotCannotBeMutatedAndWriteCopiesCallersList() {
        val input = mutableListOf(Track("a", "A", "X", 30000, ""))
        val store = LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs)
        store.write(input)
        input.clear()
        assertEquals(1, store.read().size)
        assertThrows(UnsupportedOperationException::class.java) { (store.read() as MutableList).clear() }
        assertEquals(1, LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs).read().size)
    }

    @Test fun visibilityIsNotCachedIntoRawMetadata() {
        val app = RuntimeEnvironment.getApplication()
        LibraryTrackStore(RuntimeEnvironment.getApplication(),prefs).write(listOf(Track("a", "A", "X", 30000, "")))
        val library = Library(app)
        library.setTracksHidden(setOf("a"), true)
        assertTrue(Library(app).playableTracks().isEmpty())
        library.setTracksHidden(setOf("a"), false)
        assertEquals("a", Library(app).playableTracks().single().uri)
    }
}
