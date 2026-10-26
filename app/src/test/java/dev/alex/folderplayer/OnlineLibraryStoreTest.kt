package dev.alex.folderplayer

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class OnlineLibraryStoreTest {
    @Test fun largeQueueUsesPrivateSnapshotAndRejectsStaleHandle() {
        val store=OnlineLibraryStore(RuntimeEnvironment.getApplication())
        val rows=JSONArray((1..150).map { JSONObject().put("id",it).put("title","Song $it").put("cover","https://avatars.yandex.net/image") })
        val id=store.saveQueue(rows)
        val items=OnlinePlayback.items(store.queue(id)!!)
        assertEquals(150,items.size)
        assertEquals("afp-yandex://track/150",items.last().mediaId)
        assertNull(store.queue("wrong-id"))
        assertFalse(store.queue(id)!!.contains("get-mp3"))
    }
    @Test fun changingSessionDropsPreviousCatalogue() {
        val store=OnlineLibraryStore(RuntimeEnvironment.getApplication())
        store.setSession("one");store.write("likes",JSONObject().put("tracks",JSONArray()))
        assertNotNull(store.read("likes"));store.setSession("two");assertNull(store.read("likes"))
    }
    @Test fun appendedPlaylistPagesPreserveDuplicateTracks() {
        val merged=OnlineSection.merge(JSONArray("[{\"id\":1}]"),JSONArray("[{\"id\":1},{\"id\":2}]"))
        assertEquals(3,merged.length())
        assertNull(OnlineArtwork.valid("https://avatars.yandex.net.evil.example/a"))
    }
}
