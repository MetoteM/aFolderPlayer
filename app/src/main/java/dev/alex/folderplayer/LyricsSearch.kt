package dev.alex.folderplayer

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Arrays
import java.util.Comparator
import java.util.HashSet
import java.util.Locale
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One explicit, bounded request. No automatic retries or music uploads. */
class LyricsSearch(
    val connections: Connections = Connections { url ->
        url.openConnection() as HttpURLConnection
    },
) {
    fun interface Connections {
        @Throws(IOException::class)
        fun open(url: java.net.URL): HttpURLConnection
    }

    private var nextAllowedAt = 0L

    @Synchronized
    fun cooldown(): Long = nextAllowedAt

    @Synchronized
    fun restoreCooldown(value: Long) {
        nextAllowedAt = maxOf(nextAllowedAt, value)
    }

    class Cancel {
        private var cancelled = false
        private var active: HttpURLConnection? = null
        private val deadline = System.nanoTime() + 45_000_000_000L

        @Synchronized
        @Throws(IOException::class)
        internal fun remaining(): Int {
            check()
            return maxOf(1, minOf(8000, (deadline - System.nanoTime()) / 1_000_000L)).toInt()
        }

        @Synchronized
        fun cancel() {
            cancelled = true
            active?.disconnect()
        }

        @Synchronized
        @Throws(IOException::class)
        internal fun attach(c: HttpURLConnection) {
            if (cancelled) {
                c.disconnect()
                throw IOException("Поиск отменён")
            }
            active = c
        }

        @Synchronized
        @Throws(IOException::class)
        internal fun check() {
            if (cancelled || Thread.currentThread().isInterrupted())
                throw IOException("Поиск отменён")
            if (System.nanoTime() > deadline)
                throw SocketTimeoutException("Поиск занял слишком долго")
        }

        @Synchronized
        internal fun clear() {
            active = null
        }
    }

    class Result(o: JSONObject) {
        val id: Long
        val title: String
        val artist: String
        val album: String
        val text: String
        val duration: Double
        val timed: Boolean
        val instrumental: Boolean

        init {
            id = o.getLong("id")
            title = o.optString("trackName", "")
            artist = o.optString("artistName", "")
            album = o.optString("albumName", "")
            duration = o.optDouble("duration", 0.0)
            instrumental = o.optBoolean("instrumental", false)
            val sync = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics", "")
            val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics", "")
            var parsed: LyricsDocument? = null
            if (sync.trim().isNotEmpty()) {
                try {
                    parsed = LyricsDocument(sync)
                } catch (ignored: IllegalArgumentException) {}
            }
            timed = parsed != null && parsed.cues.isNotEmpty()
            text = if (timed) sync else plain
            if (text.isNotEmpty()) LyricsDocument(text)
        }
    }

    @Throws(IOException::class)
    fun beforeRequest() {
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (now < nextAllowedAt)
                throw IOException(
                    "Подожди " + ((nextAllowedAt - now + 999) / 1000) +
                        " с перед следующим поиском"
                )
            nextAllowedAt = now + 500
        }
    }

    @Throws(Exception::class)
    fun search(title: String, artist: String, durationMs: Long, cancel: Cancel): List<Result> {
        return request(url(title, artist), durationMs, cancel)
    }

    @Throws(Exception::class)
    private fun request(url: java.net.URL, durationMs: Long, cancel: Cancel): List<Result> {
        beforeRequest()
        cancel.check()
        val c = connections.open(url)
        cancel.attach(c)
        try {
            c.connectTimeout = cancel.remaining()
            c.readTimeout = cancel.remaining()
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "FolderPlayer/0.5.0 (personal Android music player)")
            c.setRequestProperty("Accept", "application/json")
            val status = c.responseCode
            cancel.check()
            if (status == 429 || status == 503) {
                val wait = retryMillis(c.getHeaderField("Retry-After"), System.currentTimeMillis())
                synchronized(this) {
                    nextAllowedAt =
                        if (wait > Long.MAX_VALUE - System.currentTimeMillis()) Long.MAX_VALUE
                        else System.currentTimeMillis() + wait
                }
                throw IOException(
                    (if (status == 503) "Сервис текстов временно недоступен"
                     else "LRCLIB ограничил запросы") +
                        ". Повтори через " + (wait / 1000) + " с"
                )
            }
            if (status != 200)
                throw IOException("LRCLIB недоступен (HTTP $status). Попробуй позже")
            val stream =
                if ("gzip".equals(c.contentEncoding, ignoreCase = true))
                    GZIPInputStream(c.inputStream)
                else c.inputStream
            stream.use {
                val body = ByteArrayOutputStream()
                val bytes = ByteArray(4096)
                while (true) {
                    val n = it.read(bytes)
                    if (n == -1) break
                    cancel.check()
                    if (body.size() + n > MAX_RESPONSE)
                        throw IOException("Ответ слишком большой. Уточни название и исполнителя")
                    body.write(bytes, 0, n)
                }
                return parse(String(body.toByteArray(), StandardCharsets.UTF_8), durationMs)
            }
        } finally {
            c.disconnect()
            cancel.clear()
        }
    }

    /** Explicit search with at most three queries. Covers are returned for user review only. */
    @Throws(Exception::class)
    fun searchExpanded(title: String, artist: String, durationMs: Long, cancel: Cancel): List<Result> {
        url(title, artist) // Validate before normalization or network access.
        var found = search(title, artist, durationMs, cancel)
        if (found.any { r -> r.text.trim().isNotEmpty() }) return found
        val name = cleanTitle(title)
        val performer = clean(artist)
        if (name.isEmpty()) return found
        if (name != title.trim() || performer != artist.trim()) {
            awaitReady(cancel)
            found = search(name, performer, durationMs, cancel)
            if (found.any { r -> r.text.trim().isNotEmpty() }) return found
        }
        awaitReady(cancel)
        found =
            request(
                java.net.URL("https://lrclib.net/api/search?q=" + URLEncoder.encode(name, "UTF-8")),
                durationMs,
                cancel,
            )
        val useful = ArrayList<Result>()
        val seen = HashSet<String>()
        for (r in found) {
            if (r.text.trim().isNotEmpty() && related(name, r.title)) {
                val key =
                    clean(r.artist) + "|" +
                        LyricsDocument(r.text).plain.replace(Regex("\\s+"), " ").trim()
                if (seen.add(key)) useful.add(r)
            }
        }
        useful.sortWith(
            Comparator { r1, r2 ->
                (if (clean(r1.artist) == performer) 0 else 1) -
                    (if (clean(r2.artist) == performer) 0 else 1)
            }
        )
        return useful
    }

    @Throws(Exception::class)
    private fun awaitReady(cancel: Cancel) {
        while (true) {
            cancel.check()
            val remaining = cooldown() - System.currentTimeMillis()
            if (remaining <= 0) return
            Thread.sleep(minOf(50, remaining))
        }
    }

    companion object {
        const val MAX_RESPONSE: Int = 2 * 1024 * 1024

        @JvmStatic
        @Throws(IOException::class)
        fun url(titleIn: String, artistIn: String): java.net.URL {
            val title = titleIn.trim()
            val artist = artistIn.trim()
            if (title.isEmpty()) throw IOException("Введи название песни")
            if (title.length > 200 || artist.length > 200)
                throw IOException("Запрос слишком длинный")
            return java.net.URL(
                "https://lrclib.net/api/search?track_name=" +
                    URLEncoder.encode(title, "UTF-8") +
                    (if (artist.isEmpty()) ""
                     else "&artist_name=" + URLEncoder.encode(artist, "UTF-8"))
            )
        }

        @JvmStatic
        fun retryMillis(header: String?, now: Long): Long {
            if (header != null) {
                try {
                    val seconds = header.trim().toLong()
                    if (seconds >= 0 && seconds < Long.MAX_VALUE / 1000)
                        return maxOf(1000, seconds * 1000)
                } catch (ignored: NumberFormatException) {}
                try {
                    val f = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
                    f.isLenient = false
                    return maxOf(1000, f.parse(header)!!.time - now)
                } catch (ignored: Exception) {}
            }
            return 60000
        }

        @JvmStatic
        @Throws(Exception::class)
        fun parse(body: String, durationMs: Long): List<Result> {
            if (body.toByteArray(StandardCharsets.UTF_8).size > MAX_RESPONSE)
                throw IOException("Ответ слишком большой")
            val data = JSONArray(body)
            val results = ArrayList<Result>()
            val seen = HashSet<Long>()
            for (i in 0 until minOf(data.length(), 100)) {
                try {
                    val result = Result(data.getJSONObject(i))
                    if (seen.add(result.id) &&
                            (result.text.trim().isNotEmpty() || result.instrumental)
                    )
                        results.add(result)
                } catch (ignored: JSONException) {
                } catch (ignored: IllegalArgumentException) {}
            }
            results.sortWith(
                Comparator.comparingDouble { result: Result ->
                    if (durationMs > 0 && result.duration > 0 && result.duration.isFinite())
                        Math.abs(result.duration - durationMs / 1000.0)
                    else Double.MAX_VALUE
                }
            )
            return results.subList(0, minOf(20, results.size))
        }

        @JvmStatic
        fun clean(value: String): String =
            Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace(Regex("[\\p{Cf}\\p{Cc}]"), " ")
                .replace('ё', 'е')
                .replace('Ё', 'Е')
                .replace(Regex("\\s+"), " ")
                .trim()
                .lowercase(Locale.ROOT)

        @JvmStatic
        fun cleanTitle(value: String): String =
            clean(value)
                .replace(
                    Regex("(?i)\\s*\\([^)]*(?:remaster|official|audio|video|feat\\.|ft\\.|ремастер)[^)]*\\)"),
                    "",
                )
                .replace(Regex("(?i)\\s+(?:feat\\.|ft\\.)\\s+.*$"), "")
                .replace(Regex("[\\p{Punct}«»“”‘’]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()

        @JvmStatic
        fun related(requested: String, candidate: String): Boolean {
            val wanted = HashSet(Arrays.asList(*cleanTitle(requested).split(" ").toTypedArray()))
            val actual = HashSet(Arrays.asList(*cleanTitle(candidate).split(" ").toTypedArray()))
            wanted.remove("")
            actual.remove("")
            if (wanted.isEmpty()) return false
            val shared = HashSet(wanted)
            shared.retainAll(actual)
            return shared.size >= maxOf(1, Math.ceil(wanted.size * .75).toInt()) &&
                shared.size >= maxOf(1, Math.ceil(actual.size * .6).toInt())
        }
    }
}
