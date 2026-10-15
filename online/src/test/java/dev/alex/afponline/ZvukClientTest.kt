package dev.alex.afponline

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ZvukClientTest {
    @Test
    fun musicApiCheckDoesNotDependOnProfileOrClaimIdentity() {
        var requests = 0
        val client =
            ZvukClient("local-session") { path, raw, _ ->
                requests++
                assertEquals("/api/v1/graphql", path)
                assertEquals("userCollection", JSONObject(raw!!).getString("operationName"))
                "{\"data\":{\"collection\":{\"tracks\":[]}}}"
            }
        client.checkMusicApi()
        assertEquals(1, requests)
    }

    @Test
    fun deniedMusicApiIsNotRetriedOrReportedAsSuccess() {
        var requests = 0
        val client =
            ZvukClient("local-session") { _, _, _ ->
                requests++
                "{\"errors\":[{\"message\":\"private-error\"}]}"
            }
        val error = assertThrows(ZvukClient.Problem::class.java) { client.checkMusicApi() }
        assertEquals(1, requests)
        assertFalse(error.message.orEmpty().contains("private-error"))
    }

    @Test
    fun anonymousAndMissingIdentityNeverConfirmSession() {
        for (response in
            listOf(
                "{\"result\":{}}",
                "{\"result\":{\"is_anonymous\":true}}",
                "<html>private</html>",
            )) {
            val client = ZvukClient("local-session") { _, _, _ -> response }
            assertThrows(ZvukClient.Problem::class.java) { client.checkSession() }
        }
    }

    @Test
    fun profileTokenAndRemoteErrorDoNotLeakIntoExceptions() {
        val client =
            ZvukClient("local-session") { _, _, _ ->
                "{\"errors\":[{\"message\":\"private-server-data\"}]}"
            }
        val failure = assertThrows(ZvukClient.Problem::class.java) { client.checkSession() }
        assertFalse(failure.message.orEmpty().contains("private-server-data"))
        assertFalse(failure.message.orEmpty().contains("local-session"))
    }

    @Test
    fun searchExtractsOnlyTracksAndDoesNotPutQueryIntoOperationName() {
        val client =
            ZvukClient("local-session") { path, raw, token ->
                assertEquals("/api/v1/graphql", path)
                assertEquals("local-session", token)
                val request = JSONObject(raw!!)
                assertEquals("quickSearch", request.getString("operationName"))
                assertEquals("A \" B", request.getJSONObject("variables").getString("query"))
                """{"data":{"quickSearch":{"content":[{"__typename":"Artist","id":"2","title":"Artist"},{"__typename":"Track","id":"3","title":"Song","artists":[{"title":"Band"}]}]}}}"""
            }
        val songs = client.search("A \" B")
        assertEquals(1, songs.size)
        assertEquals("Song", songs.single().title)
        assertEquals("Band", songs.single().artist)
    }

    @Test
    fun emptyCollectionDoesNotRequestUnboundedTrackDetails() {
        var count = 0
        val client =
            ZvukClient("local-session") { _, _, _ ->
                count++
                "{\"data\":{\"collection\":{\"tracks\":[]}}}"
            }
        assertTrue(client.collection().isEmpty())
        assertEquals(1, count)
    }

    @Test
    fun streamResolutionIsNotPlaybackAndOnlyUsesAccountApi() {
        val client =
            ZvukClient("local-session") { path, body, _ ->
                assertEquals("/api/tiny/track/stream?id=123&quality=mid", path)
                assertNull(body)
                "{\"result\":{\"stream\":\"https://media.example.org/music?signature=temporary\"}}"
            }
        assertEquals("media.example.org", client.stream("123").host)
    }

    @Test
    fun rejectsUnsafeStreamAddressesAndInvalidInput() {
        for (url in
            listOf(
                "http://cdn.example.org/a",
                "file:///secret",
                "https://user:password@cdn.example.org/a",
                "https://127.0.0.1/a",
                "https://cdn.example.org:8080/a",
            )) {
            assertThrows(ZvukClient.Problem::class.java) { ZvukClient.safeStream(url) }
        }
        assertThrows(ZvukClient.Problem::class.java) { ZvukClient("token\r\ninjected") }
        assertThrows(ZvukClient.Problem::class.java) { ZvukClient("") }
        assertThrows(ZvukClient.Problem::class.java) {
            ZvukClient("local-session").stream("../profile")
        }
    }
}
