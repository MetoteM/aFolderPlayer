package dev.alex.folderplayer

import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URLDecoder
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SearchSupportTest {
    @Test
    fun normalizesOnlyReleaseAnnotationsAndKeepsMeaningfulTitles() {
        assertEquals("Load", SearchSupport.albumTitle("Load (LP)"))
        assertEquals("Load", SearchSupport.albumTitle("Load (1996 Remastered) [FLAC]"))
        assertEquals("Live (1988)", SearchSupport.albumTitle("Live (1988)"))
        assertEquals("Album (Live)", SearchSupport.albumTitle("Album (Live)"))
        assertEquals("(LP)", SearchSupport.albumTitle("(LP)"))
    }

    @Test
    fun classifiesWrappedTlsResetTimeoutAndDnsWithoutRawStackTrace() {
        val tls =
            SearchSupport.message(
                IOException(SSLHandshakeException("Trust anchor for certification path not found")),
                "MusicBrainz",
            )
        assertTrue(tls.contains("Сертификат"))
        assertFalse(tls.contains("javax.net"))
        assertTrue(
            SearchSupport.message(SocketException("Connection reset"), "MusicBrainz")
                .contains("оборвалось")
        )
        assertTrue(
            SearchSupport.message(SocketTimeoutException(), "MusicBrainz").contains("вовремя")
        )
        assertTrue(
            SearchSupport.message(IOException("net::ERR_CONNECTION_RESET"), "MusicBrainz")
                .contains("оборвалось")
        )
        assertTrue(
            SearchSupport.message(IOException("net::ERR_TIMED_OUT"), "MusicBrainz")
                .contains("вовремя")
        )
        assertTrue(
            SearchSupport.message(UnknownHostException(), "MusicBrainz").contains("DNS")
        )
    }

    @Test
    fun browserLinksUseHttpsAndOnlyEnteredQuery() {
        val url = SearchSupport.browserUrl("Load (LP)", "Metallica & other", false)
        assertEquals("https", url.protocol)
        assertEquals("musicbrainz.eu", url.host)
        val decoded = URLDecoder.decode(url.query, "UTF-8")
        assertTrue(decoded.contains("releasegroup:\"Load\""))
        assertTrue(decoded.contains("Metallica \\& other"))
        assertFalse(decoded.contains("LP"))
        assertEquals("ru.wikipedia.org", SearchSupport.browserUrl("Load", "Metallica", true).host)
    }

    @Throws(Exception::class)
    private fun cert(): X509Certificate {
        javaClass.getResourceAsStream("/tls-test-cert.pem").use { input ->
            return CertificateFactory.getInstance("X.509").generateCertificate(input) as X509Certificate
        }
    }

    @Test
    fun diagnosticRecordsCertificateAndPropagatesTrustRejection() {
        val trace = TlsDiagnostics()
        val rejection = CertificateException("Rejected by platform")
        var calls = intArrayOf(0)
        val delegate =
            object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()

                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                    throw rejection
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                    calls[0]++
                    throw rejection
                }
            }
        val observer = trace.observing(delegate)
        try {
            observer.checkServerTrusted(arrayOf(cert()), "RSA")
            fail("Must reject")
        } catch (e: CertificateException) {
            assertSame(rejection, e)
        }
        assertEquals(1, calls[0])
        val report = trace.report(rejection, 0)
        assertTrue(report.contains("SHA-256:"))
        assertTrue(report.contains("Issuer:"))
        assertFalse(report.contains("BEGIN PRIVATE KEY"))
    }

    @Test
    fun diagnosticCannotMakeAnUntrustedCertificateTrusted() {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        var system: X509TrustManager? = null
        for (manager in f.trustManagers) {
            if (manager is X509TrustManager) system = manager
        }
        assertNotNull(system)
        val observer = TlsDiagnostics().observing(system!!)
        try {
            observer.checkServerTrusted(arrayOf(cert()), "RSA")
            fail("Must reject")
        } catch (expected: CertificateException) {}
    }
}
