package dev.alex.folderplayer

import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ExtensionCatalogueSourceTest {
    private class Reply(
        uri: URI,
        private val status: Int,
        private val bytes: ByteArray,
        val disposed: () -> Unit = {},
    ) : HttpURLConnection(uri.toURL()) {
        override fun getResponseCode() = status

        override fun getInputStream() = ByteArrayInputStream(bytes)

        override fun connect() {}

        override fun disconnect() {
            disposed()
        }

        override fun usingProxy() = false
    }

    @Test
    fun missingV2FallsBackToSignedLegacyCatalogueAndReleasesConnections() {
        val requests = mutableListOf<String>()
        var disposed = 0
        val source =
            ExtensionCatalogueSource(RuntimeEnvironment.getApplication()) { uri ->
                requests += uri.path.substringAfterLast('/')
                if (requests.size == 1) Reply(uri, 404, byteArrayOf()) { disposed++ }
                else
                    Reply(uri, 200, File("../qa/catalog-test-signed.json").readBytes()) {
                        disposed++
                    }
            }
        assertEquals(1, source.read({}, {}).catalog.version)
        assertEquals(listOf("catalog-v2.json", "catalog.json"), requests)
        assertEquals(2, disposed)
    }

    @Test
    fun invalidV2NeverSilentlyFallsBackToLegacy() {
        var calls = 0
        var disposed = false
        val source =
            ExtensionCatalogueSource(RuntimeEnvironment.getApplication()) { uri ->
                calls++
                Reply(uri, 200, "{}".toByteArray()) { disposed = true }
            }
        try {
            source.read({}, {})
            fail()
        } catch (_: Exception) {}
        assertEquals(1, calls)
        assertTrue(disposed)
    }

    @Test
    fun serverFailureIsNotMistakenForMissingV2() {
        var calls = 0
        val source =
            ExtensionCatalogueSource(RuntimeEnvironment.getApplication()) { uri ->
                calls++
                Reply(uri, 503, byteArrayOf())
            }
        try {
            source.read({}, {})
            fail()
        } catch (_: IllegalStateException) {}
        assertEquals(1, calls)
    }

    @Test
    fun onlineDoesNotPolluteLegacyCatalogueSchema() {
        val item =
            JSONObject()
                .put("id", "online")
                .put("kind", "online")
                .put("title", "Онлайн")
                .put("file", "AFP_Online_0.1.0.apk")
                .put("size", 100)
                .put("sha256", "a".repeat(64))
        val payload =
            JSONObject().put("schema", 1).put("version", 1).put("entries", JSONArray().put(item))
        try {
            ExtensionCatalog(payload)
            fail()
        } catch (_: java.io.IOException) {}
        assertEquals("online", ExtensionCatalog(payload.put("schema", 2)).entries.single().kind)
    }
}
