package dev.alex.folderplayer

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The one-shot JSON->Room migration used to trust whatever was stored under
 * "tracks": a truncated write threw during parsing and left the library
 * empty with the backup gone. Now the blob is shape-checked, parsing is
 * contained, and the raw JSON is backed up before the key is dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryMigrationTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val prefs by lazy { context.getSharedPreferences("library", Context.MODE_PRIVATE) }

    private fun legacyJson(uri: String = "content://media/1") =
        """[{"uri":"$uri","title":"Song","artist":"Artist","duration":120000,"folder":"/m","filename":"1.mp3","album":"Al","albumArtist":"","year":2001,"trackNumber":1,"discNumber":1,"rootUri":"content://root","sourceUri":"$uri","cueStartMs":0,"cueEndMs":0,"cueSheetUri":"","artworkUri":""}]"""

    @Before
    fun setUp() {
        LibraryDatabase.resetForTests(context)
        prefs.edit().clear().apply()
    }

    @After
    fun tearDown() {
        LibraryDatabase.resetForTests(context)
    }

    @Test
    fun legacyJsonIsImportedAndKeyDropped() {
        prefs.edit().putString("tracks", legacyJson()).apply()
        val tracks = Library(context).tracks()
        assertEquals(1, tracks.size)
        assertEquals("Song", tracks.first().title)
        assertTrue("legacy key must be removed after import", !prefs.contains("tracks"))
    }

    @Test
    fun truncatedJsonIsIgnoredWithoutCrash() {
        prefs.edit().putString("tracks", legacyJson().take(20)).apply()
        val tracks = Library(context).tracks()
        assertEquals("broken blob must not crash or import", 0, tracks.size)
        assertTrue("broken blob is kept for manual recovery", prefs.contains("tracks"))
    }

    @Test
    fun nonArrayJsonIsIgnored() {
        prefs.edit().putString("tracks", "{\"not\":\"an array\"}").apply()
        assertEquals(0, Library(context).tracks().size)
        assertTrue(prefs.contains("tracks"))
    }

    @Test
    fun successfulMigrationKeepsBackup() {
        prefs.edit().putString("tracks", legacyJson()).apply()
        Library(context).tracks()
        val backup = prefs.getString("tracksBackup", null)
        assertNotNull("raw JSON must be backed up before the key is dropped", backup)
        assertTrue(backup!!.startsWith("["))
    }
}
