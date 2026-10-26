package dev.alex.folderplayer

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class LibraryMigrationTest {
    private val app
        get() = RuntimeEnvironment.getApplication()

    private val prefs
        get() = app.getSharedPreferences("library", Context.MODE_PRIVATE)

    private fun track(id: String) =
        Track(
            id,
            "Title $id",
            "Artist",
            30000,
            "Music",
            filename = "image.flac",
            album = "Album",
            year = 1991,
            trackNumber = 2,
            discNumber = 1,
            rootUri = "root",
            sourceUri = "source",
            cueStartMs = 5000,
            cueEndMs = 12000,
            cueSheetUri = "cue",
            artworkUri = "cover",
        )

    @Test
    fun importsAllMetadataAndRemovesOnlyLegacyTracksAfterCommit() {
        val tracks = listOf(track("a"), track("b"))
        prefs
            .edit()
            .putString("tracks", JSONArray(tracks.map { it.json() }).toString())
            .putString("roots", "[\"root\"]")
            .putString("playlists", "{\"P\":[\"a\"]}")
            .putStringSet("hiddenTracks", setOf("a"))
            .putStringSet("excludedTracks", setOf("b"))
            .commit()
        val db = LibraryDatabase(app)
        assertEquals(tracks, db.read(prefs))
        assertFalse(prefs.contains("tracks"))
        db.close()
        val reopened = LibraryDatabase(app)
        try {
            assertEquals(tracks, reopened.read(prefs))
        } finally {
            reopened.close()
        }
        assertEquals("[\"root\"]", prefs.getString("roots", null))
        assertEquals("{\"P\":[\"a\"]}", prefs.getString("playlists", null))
        assertEquals(setOf("a"), prefs.getStringSet("hiddenTracks", null))
        assertEquals(setOf("b"), prefs.getStringSet("excludedTracks", null))
    }

    @Test
    fun failedReplacementRollsBackAndDoesNotPublishPartialCache() {
        val db = LibraryDatabase(app)
        try {
            db.write(listOf(track("original")), prefs)
            assertThrows(android.database.sqlite.SQLiteConstraintException::class.java) {
                db.write(listOf(track("duplicate"), track("duplicate")), prefs)
            }
            assertEquals(listOf(track("original")), db.read(prefs))
        } finally {
            db.close()
        }
        val reopened = LibraryDatabase(app)
        try {
            assertEquals(listOf(track("original")), reopened.read(prefs))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun malformedLegacySourceIsPreservedAndCannotEraseCommittedRows() {
        val db = LibraryDatabase(app)
        try {
            db.write(listOf(track("a")), prefs)
            prefs.edit().putString("tracks", "broken JSON").commit()
            assertThrows(org.json.JSONException::class.java) { db.read(prefs) }
            assertEquals("broken JSON", prefs.getString("tracks", null))
            prefs.edit().remove("tracks").commit()
            assertEquals(listOf(track("a")), db.read(prefs))
        } finally {
            db.close()
        }
    }

    @Test
    fun largeImportSurvivesReopenWithoutPreferencePayload() {
        val tracks = (1..5000).map { track(it.toString()) }
        prefs.edit().putString("tracks", JSONArray(tracks.map { it.json() }).toString()).commit()
        val db = LibraryDatabase(app)
        assertEquals(tracks, db.read(prefs))
        db.close()
        assertFalse(prefs.contains("tracks"))
        val reopened = LibraryDatabase(app)
        try {
            assertEquals(tracks, reopened.read(prefs))
        } finally {
            reopened.close()
        }
    }

    @Test
    fun failedPreferenceCleanupCannotReimportAnOlderLibraryAfterRestart() {
        val original = JSONArray(listOf(track("old").json())).toString()
        prefs.edit().putString("tracks", original).commit()
        val blocked =
            java.lang.reflect.Proxy.newProxyInstance(
                android.content.SharedPreferences::class.java.classLoader,
                arrayOf(android.content.SharedPreferences::class.java),
            ) { _, method, args ->
                if (method.name == "edit") {
                    val editor = prefs.edit()
                    java.lang.reflect.Proxy.newProxyInstance(
                        android.content.SharedPreferences.Editor::class.java.classLoader,
                        arrayOf(android.content.SharedPreferences.Editor::class.java),
                    ) { proxy, edit, values ->
                        when (edit.name) {
                            "remove" -> proxy
                            "commit" -> false
                            else -> edit.invoke(editor, *(values ?: emptyArray()))
                        }
                    }
                } else method.invoke(prefs, *(args ?: emptyArray()))
            } as android.content.SharedPreferences
        val db = LibraryDatabase(app)
        assertEquals(listOf(track("old")), db.read(blocked))
        db.write(listOf(track("new")), blocked)
        db.close()
        assertEquals(original, prefs.getString("tracks", null))
        val reopened = LibraryDatabase(app)
        try {
            assertEquals(listOf(track("new")), reopened.read(blocked))
        } finally {
            reopened.close()
        }
        assertEquals(original, java.io.File(app.filesDir, "library-legacy-tracks.json").readText())
    }
}
