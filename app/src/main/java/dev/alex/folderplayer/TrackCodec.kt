package dev.alex.folderplayer

import org.json.JSONArray

internal fun decodeLegacyTracks(json: String): List<Track> {
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
                it.optLong("cueEndMs", -1).takeIf { end -> end > 0 },
                it.optString("cueSheetUri", ""),
                it.optString("artworkUri", ""),
            )
        }
    }
}
