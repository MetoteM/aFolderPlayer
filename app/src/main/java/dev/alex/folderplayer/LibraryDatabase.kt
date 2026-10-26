package dev.alex.folderplayer

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest
import java.util.Collections

/** A failed import leaves both the previous transaction and legacy source intact. */
internal class LibraryDatabase(private val context: Context) :
    SQLiteOpenHelper(context, "library-tracks.db", null, 1) {
    @Volatile private var snapshot: List<Track>? = null
    private val legacyBackup by lazy {
        android.util.AtomicFile(java.io.File(context.filesDir, "library-legacy-tracks.json"))
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE tracks (
            uri TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL,
            duration INTEGER NOT NULL, folder TEXT NOT NULL, filename TEXT NOT NULL,
            album TEXT NOT NULL, albumArtist TEXT NOT NULL, year INTEGER, trackNumber INTEGER,
            discNumber INTEGER, rootUri TEXT NOT NULL, sourceUri TEXT NOT NULL,
            cueStartMs INTEGER NOT NULL, cueEndMs INTEGER, cueSheetUri TEXT NOT NULL,
            artworkUri TEXT NOT NULL, sequence INTEGER NOT NULL)"""
        )
        db.execSQL("CREATE INDEX tracks_root ON tracks(rootUri)")
        db.execSQL("CREATE INDEX tracks_title ON tracks(title COLLATE NOCASE)")
        db.execSQL("CREATE TABLE metadata (name TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        error("Unsupported library database migration $oldVersion → $newVersion")
    }

    fun read(prefs: SharedPreferences): List<Track> {
        if (prefs.getString("tracks", null) == null)
            snapshot?.let {
                return it
            }
        return synchronized(this) {
            val db = writableDatabase
            val legacy = prefs.getString("tracks", null)
            if (legacy != null) {
                val hash = hash(legacy)
                val imported =
                    db.rawQuery("SELECT value FROM metadata WHERE name='legacyHash'", null).use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                if (hash != imported) {
                    val decoded = decodeLegacyTracks(legacy)
                    if (!legacyBackup.baseFile.exists()) AtomicTextFiles.write(legacyBackup, legacy)
                    replace(db, decoded, hash)
                }
                // A failed preference cleanup is harmless: the durable hash prevents stale
                // reimport.
                if (prefs.getString("tracks", null) == legacy)
                    prefs.edit().remove("tracks").commit()
            }
            snapshot?.let {
                return@synchronized it
            }
            val tracks =
                db.query("tracks", null, null, null, null, null, "sequence").use { cursor ->
                    buildList { while (cursor.moveToNext()) add(track(cursor)) }
                }
            Collections.unmodifiableList(tracks).also { snapshot = it }
        }
    }

    @Synchronized
    fun write(tracks: List<Track>, prefs: SharedPreferences) {
        val legacy = prefs.getString("tracks", null)
        replace(writableDatabase, tracks, legacy?.let(::hash))
        if (legacy != null && prefs.getString("tracks", null) == legacy)
            prefs.edit().remove("tracks").commit()
    }

    private fun replace(db: SQLiteDatabase, tracks: List<Track>, legacyHash: String?) {
        val copy = Collections.unmodifiableList(ArrayList(tracks))
        db.beginTransaction()
        try {
            db.delete("tracks", null, null)
            copy.forEachIndexed { index, t ->
                val row =
                    ContentValues().apply {
                        put("uri", t.uri)
                        put("title", t.title)
                        put("artist", t.artist)
                        put("duration", t.duration)
                        put("folder", t.folder)
                        put("filename", t.filename)
                        put("album", t.album)
                        put("albumArtist", t.albumArtist)
                        put("year", t.year)
                        put("trackNumber", t.trackNumber)
                        put("discNumber", t.discNumber)
                        put("rootUri", t.rootUri)
                        put("sourceUri", t.sourceUri)
                        put("cueStartMs", t.cueStartMs)
                        put("cueEndMs", t.cueEndMs)
                        put("cueSheetUri", t.cueSheetUri)
                        put("artworkUri", t.artworkUri)
                        put("sequence", index)
                    }
                db.insertOrThrow("tracks", null, row)
            }
            if (legacyHash != null)
                db.insertWithOnConflict(
                    "metadata",
                    null,
                    ContentValues().apply {
                        put("name", "legacyHash")
                        put("value", legacyHash)
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        snapshot = copy
    }

    private fun track(c: Cursor): Track {
        fun string(name: String) = c.getString(c.getColumnIndexOrThrow(name))
        fun long(name: String) = c.getLong(c.getColumnIndexOrThrow(name))
        fun number(name: String): Int? {
            val i = c.getColumnIndexOrThrow(name)
            return if (c.isNull(i)) null else c.getInt(i)
        }
        val end = c.getColumnIndexOrThrow("cueEndMs")
        return Track(
            string("uri"),
            string("title"),
            string("artist"),
            long("duration"),
            string("folder"),
            string("filename"),
            string("album"),
            string("albumArtist"),
            number("year"),
            number("trackNumber"),
            number("discNumber"),
            string("rootUri"),
            string("sourceUri"),
            long("cueStartMs"),
            if (c.isNull(end)) null else c.getLong(end),
            string("cueSheetUri"),
            string("artworkUri"),
        )
    }

    private fun hash(value: String) =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString(
            ""
        ) {
            "%02x".format(it)
        }
}
