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
class OnlineLibraryActionsTest {
    @Test fun favoritesAndHiddenKeepMetadataAcrossInstancesAndAreAccountScoped() {
        val context=RuntimeEnvironment.getApplication();val store=OnlineLibraryStore(context);store.setSession("one")
        val rows=JSONArray("""[{"id":"1","title":"Alpha","duration":123000},{"id":"2","title":"Beta"}]""")
        val actions=OnlineLibraryActions(context)
        actions.set("favorites",rows,true);actions.set("hidden",JSONArray().put(rows.getJSONObject(1)),true)
        val restored=OnlineLibraryActions(context)
        assertEquals(setOf("1","2"),restored.ids("favorites"));assertEquals(setOf("2"),restored.ids("hidden"))
        assertEquals(123000L,restored.rows("favorites").getJSONObject(0).getLong("duration"))
        assertEquals(listOf("1"),OnlineRows.display(rows,"",0,restored.ids("hidden")).map { it.song.getString("id") })
        actions.set("hidden",JSONArray().put(rows.getJSONObject(1)),false);assertTrue(restored.ids("hidden").isEmpty())
        store.setSession("two");assertTrue(restored.ids("favorites").isEmpty());assertEquals(0,restored.rows("favorites").length())
    }
    @Test fun filterAndSortPreserveSourceIndexesAndRepeatedOccurrences() {
        val rows=JSONArray("""[{"id":"1","title":"Track 10","artist":"B","duration":100},{"id":"2","title":"Track 2","artist":"A","duration":200},{"id":"1","title":"Track 10","artist":"B","duration":100}]""")
        val displayed=OnlineRows.display(rows,"TRACK",1,emptySet())
        assertEquals(listOf(1,0,2),displayed.map { it.sourceIndex })
        assertEquals(listOf(0,2),OnlineRows.display(rows,"B",3,emptySet()).map { it.sourceIndex })
    }
    @Test fun pendingQueuesDoNotOverwriteTheCurrentlyPlayingMetadata() {
        val store=OnlineLibraryStore(RuntimeEnvironment.getApplication())
        val first=store.saveQueue(JSONArray("""[{"id":"1"}]"""));val second=store.saveQueue(JSONArray("""[{"id":"2"}]"""))
        assertEquals("1",JSONArray(store.queue(first)).getJSONObject(0).getString("id"))
        assertEquals("2",JSONArray(store.queue(second)).getJSONObject(0).getString("id"))
        store.dropQueue(first);assertNull(store.queue(first));assertNotNull(store.queue(second))
        assertNotEquals(OnlineLibraryStore.playlistKey("42:7"),OnlineLibraryStore.playlistKey("99:7"))
    }
}
