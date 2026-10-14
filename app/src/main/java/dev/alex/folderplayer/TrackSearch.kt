package dev.alex.folderplayer

import android.content.Context
import java.net.URL
import java.net.URLEncoder
import java.text.Normalizer
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

internal data class SongQuery(val title: String, val artist: String)

internal class SearchIdentity(context: Context) {
    private val prefs = context.getSharedPreferences("search-identities", 0)

    fun read(uri: String): SongQuery? =
        runCatching {
                val row = JSONObject(prefs.getString(LyricsDocument.key(uri), null) ?: return null)
                SongQuery(row.getString("title"), row.getString("artist"))
            }
            .getOrNull()

    fun save(uri: String, value: SongQuery) {
        require(value.title.isNotBlank() && value.title.length <= 200 && value.artist.length <= 200)
        check(
            prefs
                .edit()
                .putString(
                    LyricsDocument.key(uri),
                    JSONObject().put("title", value.title).put("artist", value.artist).toString(),
                )
                .commit()
        )
    }
}

/** Search strings only: original tags, meaningful version markers and punctuation are retained. */
internal object SongNames {
    fun clean(value: String): String {
        var s =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace(Regex("[\\p{Cf}\\p{Cc}]"), " ")
        s = s.replace(Regex("(?i)(?:https?://|www\\.)[^\\s\\]\\)]+"), " ")
        s =
            s.replace(
                Regex(
                    "(?i)\\b[\\p{L}\\p{N}-]+\\.(?:com|net|org|ru|info|cc|me|xyz)(?:/[^\\s\\]\\)]*)?\\b"
                ),
                " ",
            )
        s =
            s.replace(
                Regex(
                    "(?i)[\\[(]\\s*(?:[0-9]{2,4}\\s*(?:kbps|кбит/?с)|mp3|flac|official (?:audio|video)|download|скачать)\\s*[\\])]"
                ),
                " ",
            )
        s = s.replace(Regex("[\\[(]\\s*[\\])]"), " ")
        return s.replace(Regex("_+"), " ").replace(Regex("\\s+"), " ").trim().take(200)
    }

    fun queries(track: Track, saved: SongQuery? = null): List<SongQuery> {
        if (saved != null) return listOf(saved, SongQuery(saved.title, "")).distinct()
        val title = clean(track.title)
        val artist =
            clean(track.artist)
                .takeUnless {
                    it.lowercase(Locale.ROOT) in
                        setOf("unknown", "unknown artist", "неизвестный исполнитель", "<unknown>")
                }
                .orEmpty()
                .ifBlank { clean(track.albumArtist) }
        val file =
            clean(
                track.filename
                    .replace(Regex("(?i)\\.(?:mp3|flac|m4a|aac|ogg|opus|wav)$"), "")
                    .replace(Regex("^\\s*\\d{1,3}[._-]+\\s*"), "")
            )
        val split = file.split(Regex("\\s+[-–—]\\s+"), limit = 2)
        val fromFile =
            if (split.size == 2 && split.all { it.isNotBlank() }) SongQuery(split[1], split[0])
            else SongQuery(file, artist)
        return (listOf(SongQuery(title, artist), fromFile, SongQuery(title, "")))
            .filter { it.title.isNotBlank() }
            .distinct()
    }

    fun similarity(a: String, b: String): Double {
        val x = LyricsMatch.key(a)
        val y = LyricsMatch.key(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        var previous = IntArray(y.length + 1) { it }
        for (i in x.indices) {
            val next = IntArray(y.length + 1)
            next[0] = i + 1
            for (j in y.indices) next[j + 1] =
                minOf(next[j] + 1, previous[j + 1] + 1, previous[j] + if (x[i] == y[j]) 0 else 1)
            previous = next
        }
        return 1.0 - previous[y.length].toDouble() / maxOf(x.length, y.length)
    }
}

internal class RecordingSearch(private val connections: LyricsSearch.Connections) {
    private val gate = MusicBrainzSearch(connections)

    data class Match(val query: SongQuery, val duration: Long)

    fun search(query: SongQuery, cancel: LyricsSearch.Cancel): List<Match> {
        cancel.check()
        gate.beforeRequest()
        fun fuzzy(value: String) =
            Regex("[\\p{L}\\p{N}]+")
                .findAll(value)
                .map { it.value.take(80) + (if (it.value.length >= 4) "~" else "") }
                .take(12)
                .joinToString(" AND ")
        val title = fuzzy(query.title)
        if (title.isBlank()) return emptyList()
        val artist = fuzzy(query.artist)
        val q = "recording:($title)" + if (artist.isBlank()) "" else " AND artist:($artist)"
        val c =
            connections.open(
                URL(
                    "https://musicbrainz.eu/ws/2/recording/?fmt=json&limit=5&query=" +
                        URLEncoder.encode(q, "UTF-8")
                )
            )
        cancel.attach(c)
        try {
            c.connectTimeout = cancel.remaining()
            c.readTimeout = cancel.remaining()
            c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty(
                "User-Agent",
                "aFolderPlayer/0.7.9 (https://github.com/MetoteM/aFolderPlayer)",
            )
            check(c.responseCode == 200) { "MusicBrainz временно недоступен" }
            val body = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(4096)
                while (true) {
                    cancel.check()
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(body.size() + n <= 1024 * 1024)
                    body.write(buffer, 0, n)
                }
            }
            val rows = JSONObject(body.toString("UTF-8")).optJSONArray("recordings") ?: JSONArray()
            return (0 until minOf(rows.length(), 5)).mapNotNull { i ->
                runCatching {
                        val r = rows.getJSONObject(i)
                        val credits = r.optJSONArray("artist-credit") ?: JSONArray()
                        val performer =
                            (0 until credits.length()).joinToString("") { j ->
                                val a = credits.getJSONObject(j)
                                a.optString(
                                    "name",
                                    a.optJSONObject("artist")?.optString("name").orEmpty(),
                                ) + a.optString("joinphrase")
                            }
                        Match(SongQuery(r.getString("title"), performer), r.optLong("length", 0))
                    }
                    .getOrNull()
            }
        } finally {
            c.disconnect()
            cancel.clear()
        }
    }
}

/** At most three lyric requests and one metadata request, all user initiated. */
internal class TrackSearch(
    private val lyrics: LyricsSearch,
    private val metadata: (SongQuery, LyricsSearch.Cancel) -> List<RecordingSearch.Match>,
) {
    constructor(
        context: Context,
        lyrics: LyricsSearch,
    ) : this(lyrics, RecordingSearch(SearchConnections(context))::search)

    fun search(
        track: Track,
        saved: SongQuery?,
        cancel: LyricsSearch.Cancel,
    ): List<LyricsSearch.Result> {
        val queries = SongNames.queries(track, saved)
        if (queries.isEmpty()) return emptyList()
        val found = linkedMapOf<Long, LyricsSearch.Result>()
        var calls = 0
        fun request(q: SongQuery) {
            cancel.check()
            while (System.currentTimeMillis() < lyrics.cooldown()) {
                cancel.check()
                val remaining = lyrics.cooldown() - System.currentTimeMillis()
                if (remaining > 1500)
                    throw java.io.IOException("Сервис ограничил запросы. Повтори позже")
                Thread.sleep(minOf(50, remaining.coerceAtLeast(1)))
            }
            calls++
            for (r in lyrics.search(q.title, q.artist, track.duration, cancel)) if (
                r.text.isNotBlank()
            )
                found[r.id] = r
        }
        for (q in queries.take(2)) {
            request(q)
            if (
                LyricsMatch.automatic(
                    found.values.toList(),
                    q.title,
                    q.artist,
                    track.album,
                    track.duration,
                ) != null
            )
                break
        }
        fun score(r: LyricsSearch.Result): Double =
            queries.maxOf { q ->
                val title = SongNames.similarity(q.title, r.title)
                val artist =
                    if (q.artist.isBlank()) 0.0 else SongNames.similarity(q.artist, r.artist)
                title * .75 +
                    artist * .2 +
                    if (
                        track.duration > 0 &&
                            r.duration > 0 &&
                            kotlin.math.abs(track.duration / 1000.0 - r.duration) < 5
                    )
                        .05
                    else 0.0
            }
        if (found.values.none { score(it) >= .92 } && calls < 3) {
            val candidates =
                try {
                    metadata(queries.first(), cancel)
                } catch (_: Exception) {
                    cancel.check()
                    emptyList()
                }
            val best =
                candidates
                    .filter {
                        SongNames.similarity(queries.first().title, it.query.title) >= .6 &&
                            (track.duration <= 0 ||
                                it.duration <= 0 ||
                                kotlin.math.abs(track.duration - it.duration) <= 10000)
                    }
                    .maxByOrNull {
                        SongNames.similarity(queries.first().title, it.query.title) +
                            SongNames.similarity(queries.first().artist, it.query.artist)
                    }
            if (best != null) request(best.query) else if (queries.size > 2) request(queries.last())
        }
        cancel.check()
        return found.values
            .filter { r -> queries.any { SongNames.similarity(it.title, r.title) >= .6 } }
            .sortedByDescending(::score)
            .take(5)
    }
}
