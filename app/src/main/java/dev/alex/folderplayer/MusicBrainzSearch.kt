package dev.alex.folderplayer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.util.zip.GZIPInputStream
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.ArrayList
import java.util.HashSet
import java.util.Locale
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Explicit album search, bounded response, no retries, no audio uploads. */
class MusicBrainzSearch(
    private val connections: LyricsSearch.Connections = defaultConnections(),
) {
    private var retryUntil = 0L

    @Synchronized
    fun cooldown(): Long = retryUntil

    @Synchronized
    fun restoreCooldown(value: Long) {
        retryUntil = maxOf(retryUntil, value)
    }

    @Throws(IOException::class)
    fun beforeRequest() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now < retryUntil)
                throw IOException(
                    "MusicBrainz: повтори поиск через " + ((retryUntil - now + 999) / 1000) + " с"
                )
        }
        reserve(now)
    }

    @Throws(Exception::class)
    fun search(album: String, artist: String, cancel: LyricsSearch.Cancel): List<Result> {
        val url = url(album, artist)
        cancel.check()
        beforeRequest()
        val c = connections.open(url)
        cancel.attach(c)
        val started = System.nanoTime()
        try {
            c.connectTimeout = 10000
            c.readTimeout = 30000
            c.instanceFollowRedirects = false
            c.setRequestProperty(
                "User-Agent",
                "FolderPlayer/0.5.0 (Alex personal Android music player; source distributed with APK)",
            )
            c.setRequestProperty("Accept", "application/json")
            val status = c.responseCode
            cancel.check()
            if (status == 429 || status == 503) {
                val wait =
                    LyricsSearch.retryMillis(
                        c.getHeaderField("Retry-After"),
                        System.currentTimeMillis(),
                    )
                synchronized(this) {
                    val clock = System.currentTimeMillis()
                    retryUntil =
                        if (wait > Long.MAX_VALUE - clock) Long.MAX_VALUE else clock + wait
                }
                throw IOException("MusicBrainz занят. Повтори через " + (wait / 1000) + " с")
            }
            if (status != 200)
                throw IOException("MusicBrainz недоступен (HTTP $status). Попробуй позже")
            val stream =
                if ("gzip".equals(c.contentEncoding, ignoreCase = true))
                    GZIPInputStream(c.inputStream)
                else c.inputStream
            stream.use {
                val body = ByteArrayOutputStream()
                val b = ByteArray(4096)
                while (true) {
                    val n = it.read(b)
                    if (n == -1) break
                    cancel.check()
                    if (System.nanoTime() - started > 45_000_000_000L)
                        throw SocketTimeoutException("Поиск занял слишком долго")
                    if (body.size() + n > MAX_RESPONSE)
                        throw IOException("Ответ слишком большой. Уточни запрос")
                    body.write(b, 0, n)
                }
                cancel.check()
                try {
                    return parse(String(body.toByteArray(), StandardCharsets.UTF_8))
                } catch (malformed: JSONException) {
                    throw IOException(
                        "MusicBrainz вернул непонятный ответ. Попробуй позже",
                        malformed,
                    )
                }
            }
        } finally {
            c.disconnect()
            cancel.clear()
        }
    }

    class Result(o: JSONObject) {
        val id: String
        val title: String
        val artist: String
        val date: String
        val type: String
        val comment: String
        val year: Int?

        init {
            id = o.getString("id")
            if (!id.matches(Regex("[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}")))
                throw JSONException("Invalid MBID")
            title = o.getString("title").trim()
            if (title.isEmpty()) throw JSONException("Empty title")
            val d = o.optString("first-release-date", "")
            date = if (d.matches(Regex("[0-9]{4}(?:-[0-9]{2}(?:-[0-9]{2})?)?"))) d else ""
            year = YearTags.parse(date)
            val credit = StringBuilder()
            val a: JSONArray? = o.optJSONArray("artist-credit")
            if (a != null) {
                for (i in 0 until a.length()) {
                    val c = a.optJSONObject(i) ?: continue
                    val artistObj = c.optJSONObject("artist")
                    credit.append(
                        c.optString(
                            "name",
                            if (artistObj == null) "" else artistObj.optString("name", ""),
                        )
                    ).append(c.optString("joinphrase", ""))
                }
            }
            artist = credit.toString()
            var t = o.optString("primary-type", "")
            val secondary: JSONArray? = o.optJSONArray("secondary-types")
            if (secondary != null) {
                for (i in 0 until secondary.length()) {
                    val part = secondary.optString(i, "")
                    if (part.isNotEmpty()) t += (if (t.isEmpty()) "" else " · ") + part
                }
            }
            type = t
            comment = o.optString("disambiguation", "")
        }

        fun sourceUrl(): String = "https://musicbrainz.eu/release-group/$id"
    }

    companion object {
        const val MAX_RESPONSE: Int = 1024 * 1024
        private var nextRequestAt = 0L

        private fun defaultConnections(): LyricsSearch.Connections =
            LyricsSearch.Connections { url -> url.openConnection() as HttpURLConnection }

        @Synchronized
        @Throws(IOException::class)
        private fun reserve(now: Long) {
            if (now < nextRequestAt) throw IOException("Подожди секунду перед следующим поиском")
            nextRequestAt = now + 1100
        }

        @JvmStatic
        @Throws(IOException::class)
        fun url(albumIn: String, artistIn: String): java.net.URL {
            val album = SearchSupport.albumTitle(albumIn)
            val artist = artistIn.trim()
            if (album.isEmpty()) throw IOException("Введи название альбома")
            if (album.length > 200 || artist.length > 200)
                throw IOException("Запрос слишком длинный")
            val query =
                "releasegroup:" +
                    literal(album) +
                    (if (artist.isEmpty()) "" else " AND artist:" + literal(artist))
            return java.net.URL(
                "https://musicbrainz.eu/ws/2/release-group/?fmt=json&limit=20&query=" +
                    URLEncoder.encode(query, "UTF-8")
            )
        }

        private fun literal(value: String): String {
            val s = StringBuilder("\"")
            for (c in value.toCharArray()) {
                if ("+-!(){}[]^\"~*?:\\/&|".indexOf(c) >= 0) s.append('\\')
                s.append(c)
            }
            return s.append('"').toString()
        }

        @JvmStatic
        @Throws(JSONException::class)
        fun parse(body: String): List<Result> {
            if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE)
                throw JSONException("Ответ слишком большой")
            val data: JSONArray = JSONObject(body).getJSONArray("release-groups")
            val found = ArrayList<Result>()
            val seen = HashSet<String>()
            for (i in 0 until minOf(data.length(), 100)) {
                if (found.size >= 20) break
                try {
                    val result = Result(data.getJSONObject(i))
                    if (seen.add(result.id.lowercase(Locale.ROOT))) found.add(result)
                } catch (ignored: JSONException) {}
            }
            return found
        }
    }
}
