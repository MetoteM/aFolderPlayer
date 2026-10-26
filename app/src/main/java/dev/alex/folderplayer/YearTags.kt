package dev.alex.folderplayer

import java.util.Locale

/**
 * One policy shared by FLAC Vorbis and MP3 ID3: original release, release date, native fallback.
 */
class YearTags {
    private var year: Int? = null
    private var rank = Int.MAX_VALUE

    fun add(key: String?, value: String?) {
        if (key == null) return
        val k =
            key.uppercase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace(" ", "")
        val priority =
            when (k) {
                "ORIGINALDATE", "ORIGINALRELEASEDATE", "TDOR" -> 0
                "ORIGINALYEAR", "ORIGINALRELEASEYEAR", "ORIGYEAR", "TORY", "TOR" -> 1
                "DATE", "RELEASEDATE", "TDRL" -> 10
                "TDRC" -> 20
                "YEAR", "RELEASEYEAR", "TYER", "TYE" -> 30
                else -> return
            }
        val parsed = parse(value)
        if (parsed != null && priority < rank) {
            year = parsed
            rank = priority
        }
    }

    fun resolve(fallback: Int?): Int? = year ?: fallback

    companion object {
        @JvmStatic
        fun parse(text: String?): Int? {
            if (text == null) return null
            val value = text.trim()
            if (value.length < 4) return null
            var year = 0
            for (i in 0 until 4) {
                val c = value[i]
                if (c < '0' || c > '9') return null
                year = year * 10 + (c - '0')
            }
            if (value.length > 4 && value[4].isDigit()) return null
            return if (year in 1000..9999) year else null
        }
    }
}
