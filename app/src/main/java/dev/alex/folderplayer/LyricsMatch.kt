package dev.alex.folderplayer

import java.text.Normalizer
import java.util.Locale

/** Auto-save only an unambiguous metadata/duration match, never the first arbitrary hit. */
object LyricsMatch {
    @JvmStatic
    fun key(value: String?): String =
        Normalizer
            .normalize(value ?: "", Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}]"), "")

    @JvmStatic
    fun automatic(
        results: List<LyricsSearch.Result>,
        title: String,
        artist: String,
        album: String,
        duration: Long,
    ): LyricsSearch.Result? {
        if (key(title).isEmpty() || key(artist).isEmpty() || duration <= 0) return null
        val seconds = duration / 1000.0
        val tolerance = minOf(5.0, maxOf(2.0, seconds * 0.02))
        var exact =
            results
                .filter {
                    it.text.isNotBlank() &&
                        key(it.title) == key(title) &&
                        key(it.artist) == key(artist) &&
                        it.duration.isFinite() &&
                        it.duration > 0 &&
                        Math.abs(it.duration - seconds) <= tolerance
                }
                .toMutableList()
        if (exact.isEmpty()) return null
        if (key(album).isNotEmpty()) {
            val clean = album.replace(Regex("(?i)\\s*\\((?:LP|CD|FLAC|MP3)\\)\\s*$"), "")
            val sameAlbum = exact.filter { key(it.album) == key(clean) }
            if (sameAlbum.isNotEmpty()) exact = sameAlbum.toMutableList()
        }
        val texts = exact.mapTo(HashSet()) { key(LyricsDocument(it.text).plain) }
        if (texts.size != 1) return null
        exact.sortWith(compareBy({ it.timed }, { Math.abs(it.duration - seconds) }))
        return exact[0]
    }
}
