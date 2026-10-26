package dev.alex.afponline

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class YandexCatalogueTest {
    @Test fun readsBeyondTwentyAndKeepsCollectionOrderWithOneIndexRequest() {
        var indexCalls=0
        val client=YandexClient("local") { url, _ ->
            when {
                url.endsWith("account/status") -> "{\"result\":{\"account\":{\"uid\":42}}}"
                url.endsWith("likes/tracks") -> {
                    indexCalls++
                    JSONObject().put("result",JSONObject().put("library",JSONObject().put("tracks",JSONArray((1..75).map { JSONObject().put("id",it) })))).toString()
                }
                else -> {
                    val ids=url.substringAfter("track-ids=").split(',').reversed()
                    JSONObject().put("result",JSONArray(ids.map { JSONObject().put("id",it).put("title","Song $it").put("durationMs",123000).put("coverUri","avatars.yandex.net/test/%%") })).toString()
                }
            }
        }
        client.checkSession()
        val first=client.collectionPage(0);val next=client.collectionPage(first.next!!)
        assertEquals(50,first.tracks.size);assertEquals(25,next.tracks.size);assertNull(next.next)
        assertEquals("1",first.tracks.first().id);assertEquals("75",next.tracks.last().id)
        assertEquals(1,indexCalls);assertEquals(123000L,first.tracks.first().duration)
        assertEquals("https://avatars.yandex.net/test/300x300",first.tracks.first().cover)
    }
    @Test fun playlistReadsKeepRepeatedTracksAndNeverWriteToService() {
        val client=YandexClient("local") { url,_ ->
            when {
                url.endsWith("account/status") -> "{\"result\":{\"account\":{\"uid\":42}}}"
                url.endsWith("likes/playlists") -> "{\"result\":[]}"
                url.endsWith("playlists/list") -> "{\"result\":[{\"kind\":7,\"title\":\"My list\",\"trackCount\":2}]}"
                url.endsWith("playlists/7") -> "{\"result\":{\"tracks\":[{\"track\":{\"id\":1,\"title\":\"One\"}},{\"track\":{\"id\":1,\"title\":\"One\"}}]}}"
                else -> throw AssertionError("Unexpected request")
            }
        }
        client.checkSession();assertEquals("42:7",client.playlists().getJSONObject(0).getString("id"))
        assertEquals(listOf("1","1"),client.playlistPage("7",0).tracks.map { it.id })
    }
    @Test fun keepsOwnersOfFollowedPlaylistsAndPagesBeyondFifty() {
        val urls=mutableListOf<String>()
        val c=YandexClient("local") { url,_ ->
            urls+=url
            when {
                url.endsWith("account/status") -> """{"result":{"account":{"uid":42}}}"""
                url.endsWith("playlists/list") -> """{"result":[{"kind":7,"title":"Own","owner":{"uid":42}}]}"""
                url.endsWith("likes/playlists") -> """{"result":[{"playlist":{"kind":7,"title":"Followed","owner":{"uid":99}}}]}"""
                url.endsWith("/users/99/playlists/7") -> JSONObject().put("result",JSONObject().put("tracks",JSONArray((1..125).map { JSONObject().put("track",JSONObject().put("id",it).put("title","Song $it")) }))).toString()
                else -> throw AssertionError(url)
            }
        }
        c.checkSession();val lists=c.playlists()
        assertEquals(listOf("42:7","99:7"),(0 until lists.length()).map { lists.getJSONObject(it).getString("id") })
        val pages=listOf(c.playlistPage("99:7",0),c.playlistPage("99:7",50),c.playlistPage("99:7",100))
        assertEquals(listOf(50,50,25),pages.map { it.tracks.size });assertEquals("125",pages.last().tracks.last().id)
        assertEquals(1,urls.count { it.endsWith("/users/99/playlists/7") })
    }
    @Test fun rejectsForeignCoverAndBadPlaylistBeforeRequest() {
        assertEquals("",YandexClient.coverUrl("https://evil.example/cover"))
        val client=YandexClient("local") { url,_ ->
            assertTrue(url.endsWith("account/status"));"{\"result\":{\"account\":{\"uid\":42}}}"
        }
        client.checkSession()
        try { client.playlistPage("../private",0);fail() } catch(_:YandexClient.Problem) { }
    }
}
