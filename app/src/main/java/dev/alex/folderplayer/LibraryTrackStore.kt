package dev.alex.folderplayer

import android.content.SharedPreferences
import org.json.JSONArray
import java.util.Collections
import java.util.WeakHashMap

/** One decoded immutable snapshot per preferences store, shared by screens and service.
 * Keep the existing format so upgrades and downgrades preserve users' libraries.
 * Visibility and manual album years are applied by Library after this raw snapshot.
 */
internal class LibraryTrackStore(private val prefs: SharedPreferences) {
    private data class Snapshot(val json: String, val tracks: List<Track>)

    fun read(): List<Track> = synchronized(snapshots) {
        val json = prefs.getString("tracks", "[]") ?: "[]"
        snapshots[prefs]?.takeIf { it.json == json }?.let { return@synchronized it.tracks }
        val tracks = Collections.unmodifiableList(runCatching { decode(json) }.getOrDefault(emptyList()))
        snapshots[prefs] = Snapshot(json, tracks)
        tracks
    }

    fun write(tracks: List<Track>) = synchronized(snapshots) {
        val copy = Collections.unmodifiableList(ArrayList(tracks))
        val json = JSONArray(copy.map { it.json() }).toString()
        prefs.edit().putString("tracks", json).apply()
        snapshots[prefs] = Snapshot(json, copy)
    }

    private fun decode(json: String): List<Track> {
        val a = JSONArray(json)
        return (0 until a.length()).map { i ->
            a.getJSONObject(i).let {
                Track(
                    it.getString("uri"),
                    it.getString("title"),
                    it.getString("artist"),
                    it.getLong("duration"),
                    it.getString("folder"),
                    it.optString("filename", ""),
                    it.optString("album", ""),
                    it.optString("albumArtist", ""),
                    it.optInt("year", 0).takeIf { n -> n in 1000..9999 },
                    it.optInt("trackNumber", 0).takeIf { n -> n > 0 },
                    it.optInt("discNumber", 0).takeIf { n -> n > 0 },
                    it.optString("rootUri", ""),
                    it.optString("sourceUri", it.getString("uri")),
                    it.optLong("cueStartMs", 0),
                    it.optLong("cueEndMs", -1).takeIf { end -> end>0 },
                    it.optString("cueSheetUri", ""),
                    it.optString("artworkUri", ""),
                )
            }
        }
    }

    companion object {
        private val snapshots = WeakHashMap<SharedPreferences, Snapshot>()
    }
}
