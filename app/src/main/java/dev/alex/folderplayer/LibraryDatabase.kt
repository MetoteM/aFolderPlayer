package dev.alex.folderplayer

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

/**
 * Room storage for the scanned library. Replaces the old scheme of keeping
 * the whole track list as one JSON string in SharedPreferences: reading is a
 * single indexed query instead of parsing a multi-megabyte array on every
 * startup, and per-track updates no longer rewrite the entire blob.
 *
 * One-way migration: on first open after upgrade the legacy JSON is imported
 * and then dropped from prefs (see Library.storedTracks).
 */
@Entity(tableName = "tracks")
internal data class TrackEntity(
    @PrimaryKey val uri: String,
    val title: String,
    val artist: String,
    val duration: Long,
    val folder: String,
    val filename: String,
    val album: String,
    val albumArtist: String,
    val year: Int,
    val trackNumber: Int,
    val discNumber: Int,
    val rootUri: String,
    val sourceUri: String,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val cueSheetUri: String,
    val artworkUri: String,
)

internal fun TrackEntity.toTrack(): Track =
    Track(
        uri = uri,
        title = title,
        artist = artist,
        duration = duration,
        folder = folder,
        filename = filename,
        album = album,
        albumArtist = albumArtist,
        year = year.takeIf { it in 1000..9999 },
        trackNumber = trackNumber.takeIf { it > 0 },
        discNumber = discNumber.takeIf { it > 0 },
        rootUri = rootUri,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs.takeIf { it > 0 },
        cueSheetUri = cueSheetUri,
        artworkUri = artworkUri,
    )

internal fun Track.toEntity(): TrackEntity =
    TrackEntity(
        uri = uri,
        title = title,
        artist = artist,
        duration = duration,
        folder = folder,
        filename = filename,
        album = album,
        albumArtist = albumArtist,
        year = year ?: 0,
        trackNumber = trackNumber ?: 0,
        discNumber = discNumber ?: 0,
        rootUri = rootUri,
        sourceUri = sourceUri,
        cueStartMs = cueStartMs,
        cueEndMs = cueEndMs ?: 0,
        cueSheetUri = cueSheetUri,
        artworkUri = artworkUri,
    )

@Dao
internal interface TrackDao {
    @Query("SELECT * FROM tracks")
    fun all(): List<TrackEntity>

    @Query("SELECT COUNT(*) FROM tracks")
    fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM tracks")
    fun clear()

    @Transaction
    fun replaceAll(tracks: List<TrackEntity>) {
        clear()
        insertAll(tracks)
    }
}

@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
internal abstract class LibraryDatabase : RoomDatabase() {
    abstract fun trackDao(): TrackDao

    companion object {
        private const val NAME = "library-db"
        @Volatile private var instance: LibraryDatabase? = null
        private var owner: Context? = null

        fun get(context: Context): LibraryDatabase = synchronized(this) {
            val app = context.applicationContext
            // Rebuild when the process-wide application changes (Robolectric tests).
            if (instance == null || owner !== app) {
                instance =
                    Room.databaseBuilder(app, LibraryDatabase::class.java, NAME)
                        .allowMainThreadQueries()
                        .build()
                owner = app
            }
            instance!!
        }

        /** Test hook: drop the cached connection so deleteDatabase() really resets. */
        fun resetForTests(context: Context) = synchronized(this) {
            runCatching { instance?.close() }
            instance = null
            owner = null
            context.deleteDatabase(NAME)
        }
    }
}
