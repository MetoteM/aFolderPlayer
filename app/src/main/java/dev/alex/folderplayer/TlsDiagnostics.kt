package dev.alex.folderplayer

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.security.GeneralSecurityException
import java.util.StringJoiner
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Collections
import java.util.Date
import java.util.IdentityHashMap
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Explicit diagnostic probe. Records only public certificate facts, and delegates every trust
 * check.
 */
class TlsDiagnostics {
    @Volatile
    private var certificates = "Сертификат не получен: соединение могло оборваться до проверки TLS."

    @Volatile
    private var addresses = "Не получены (или диагностика не начиналась)."

    @Volatile
    private var endpoint = "https://musicbrainz.eu/ws/2/release-group/"

    @Throws(Exception::class)
    fun open(url: URL): HttpURLConnection {
        if (!"https".equals(url.protocol) || "musicbrainz.eu" != url.host)
            throw IllegalArgumentException("Unexpected diagnostic host")
        endpoint = url.protocol + "://" + url.host + url.path
        try {
            val ips = StringJoiner(", ")
            val resolved = InetAddress.getAllByName(url.host)
            for (i in 0 until minOf(resolved.size, 6)) ips.add(resolved[i].hostAddress)
            addresses = ips.toString()
        } catch (missing: UnknownHostException) {
            addresses = "Системный DNS не вернул адрес."
        }
        val c = url.openConnection() as HttpsURLConnection
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as java.security.KeyStore?)
        var delegate: X509TrustManager? = null
        for (manager in factory.trustManagers) {
            if (manager is X509TrustManager) {
                delegate = manager
                break
            }
        }
        if (delegate == null) throw GeneralSecurityException("No system trust manager")
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(observing(delegate)), null)
        c.sslSocketFactory = context.socketFactory
        // Keep HttpsURLConnection's existing hostname verifier. No user CA, pins, or trust-all
        // fallback.
        return c
    }

    fun observing(delegate: X509TrustManager): X509TrustManager {
        return object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers!!

            @Throws(CertificateException::class)
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                delegate.checkClientTrusted(chain, authType)
            }

            @Throws(CertificateException::class)
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                record(chain)
                delegate.checkServerTrusted(chain, authType)
            }
        }
    }

    private fun record(chain: Array<X509Certificate>?) {
        val b = StringBuilder()
        val n = if (chain == null) 0 else minOf(chain.size, 6)
        for (i in 0 until n) {
            try {
                val c = chain!![i]
                b.append("\nСертификат ")
                    .append(i + 1)
                    .append("\nSubject: ")
                    .append(c.subjectX500Principal.getName())
                    .append("\nIssuer: ")
                    .append(c.issuerX500Principal.getName())
                    .append("\nДействует: ")
                    .append(c.notBefore)
                    .append(" — ")
                    .append(c.notAfter)
                    .append("\nSHA-256: ")
                    .append(hex(MessageDigest.getInstance("SHA-256").digest(c.encoded)))
                    .append('\n')
            } catch (ignored: Exception) {}
        }
        if (b.isNotEmpty()) certificates = b.toString()
    }

    fun report(failure: Throwable?, count: Int): String {
        val b =
            StringBuilder("Folder Player 0.1.10 · проверка MusicBrainz\nEndpoint: ")
                .append(endpoint)
                .append("\nСистемный DNS (не обязательно фактический IP соединения): ")
                .append(addresses)
                .append("\nВремя телефона: ")
                .append(Date())
                .append("\n")
                .append(
                    if (failure == null) "API ответил, результатов: $count"
                    else "API не ответил успешно."
                )
                .append('\n')
        val seen: MutableSet<Throwable> = Collections.newSetFromMap(IdentityHashMap())
        var depth = 0
        var e = failure
        while (e != null && seen.add(e) && depth++ < 6) {
            b.append(e.javaClass.simpleName)
                .append(": ")
                .append(e.message?.replace(Regex("[\\r\\n]"), " ") ?: "null")
                .append('\n')
            e = e.cause
        }
        b.append(certificates)
            .append(
                "\n" +
                    "Проверка сертификатов и имени сервера не отключалась. Данные никуда" +
                    " автоматически не отправляются."
            )
        return b.toString()
    }

    companion object {
        private fun hex(bytes: ByteArray): String {
            val b = StringBuilder()
            for (x in bytes) b.append(String.format(Locale.ROOT, "%02x", x.toInt() and 255))
            return b.toString()
        }
    }
}
