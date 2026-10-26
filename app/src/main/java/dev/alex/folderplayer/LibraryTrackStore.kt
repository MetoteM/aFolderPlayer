package dev.alex.folderplayer

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap

/** Indexed durable metadata; preferences retain only small user settings. */
internal class LibraryTrackStore(context: Context, private val prefs: SharedPreferences) {
    private val database =
        synchronized(databases) {
            databases.getOrPut(prefs) { LibraryDatabase(context.applicationContext) }
        }

    fun read(): List<Track> = database.read(prefs)

    fun write(tracks: List<Track>) = database.write(tracks, prefs)

    companion object {
        private val databases = WeakHashMap<SharedPreferences, LibraryDatabase>()
    }
}
