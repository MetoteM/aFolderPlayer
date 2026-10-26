package dev.alex.folderplayer

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.net.UnknownHostException
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import javax.net.ssl.SSLException
import java.security.cert.CertificateException

/** UI diagnostics and query cleanup only; never changes TLS trust or the audio metadata. */
object SearchSupport {
    @JvmStatic
    fun albumTitle(value: String): String {
        var clean = value.trim()
        while (true) {
            val previous = clean
            clean =
                clean
                    .replaceFirst(
                        Regex(
                            "(?i)\\s*[\\[(]\\s*(?:LP|CD|FLAC|MP3|VINYL|(?:[0-9]{4}\\s+)?REMASTER(?:ED)?(?:\\s+[0-9]{4})?)\\s*[\\])]\\s*$"
                        ),
                        ""
                    )
                    .trim()
            if (clean == previous) break
        }
        return clean.ifEmpty { value.trim() }
    }

    @JvmStatic
    fun message(error: Throwable, service: String): String {
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        var tls = false
        var reset = false
        var timeout = false
        var dns = false
        var e: Throwable? = error
        while (e != null && seen.add(e)) {
            val m = e.message?.lowercase(Locale.ROOT) ?: "null"
            tls =
                tls ||
                    e is SSLException ||
                    e is CertificateException ||
                    m.contains("trust anchor") ||
                    m.contains("certpath")
            reset =
                reset ||
                    (e is SocketException &&
                        (m.contains("reset") || m.contains("closed") || m.contains("broken pipe")))
            reset =
                reset || m.contains("err_connection_reset") || m.contains("err_connection_closed")
            timeout =
                timeout ||
                    e is SocketTimeoutException ||
                    m.contains("err_timed_out") ||
                    m.contains("err_connection_timed_out")
            dns = dns || e is UnknownHostException || m.contains("err_name_not_resolved")
            e = e.cause
        }
        if (tls)
            return "Не удалось проверить защищённое соединение с $service. Сертификат не принят или TLS-соединение не установлено. Проверь дату телефона и доступность сервиса в текущей сети."
        if (reset)
            return "Соединение с $service оборвалось. Это не результат поиска. Попробуй позже или другую сеть."
        if (timeout) return "Сервис не ответил вовремя. Попробуй позже или открой поиск в браузере."
        if (dns) return "Не удалось найти адрес $service. Проверь интернет и настройки DNS/VPN."
        return error.message ?: "Не удалось выполнить поиск"
    }

    @JvmStatic
    @Throws(IOException::class)
    fun browserUrl(album: String, artist: String, wikipedia: Boolean): java.net.URL {
        val albumClean = albumTitle(album)
        val artistClean = artist.trim()
        if (albumClean.isEmpty() || albumClean.length > 200 || artistClean.length > 200)
            throw IOException("Проверь название альбома и исполнителя")
        if (wikipedia)
            return java.net.URL(
                "https://ru.wikipedia.org/w/index.php?search=" +
                    URLEncoder.encode("$artistClean $albumClean", "UTF-8")
            )
        val query =
            "releasegroup:" +
                literal(albumClean) +
                (if (artistClean.isEmpty()) "" else " AND artist:" + literal(artistClean))
        return java.net.URL(
            "https://musicbrainz.eu/search?type=release_group&method=advanced&query=" +
                URLEncoder.encode(query, "UTF-8")
        )
    }

    private fun literal(s: String): String {
        val b = StringBuilder("\"")
        for (c in s.toCharArray()) {
            if ("+-!(){}[]^\"~*?:\\/&|".indexOf(c) >= 0) b.append('\\')
            b.append(c)
        }
        return b.append('"').toString()
    }
}
