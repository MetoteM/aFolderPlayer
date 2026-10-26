package dev.alex.folderplayer

import android.os.Bundle
import androidx.media3.common.Player
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class IntegrationCompatibilityTest {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun player(service:PlaybackService)=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service) as Player
    @Test fun localHideAndExclusionLeaveOnlineItemsEligibleAndUseSeparateDecisions() {
        val library=Library(context)
        val items=listOf("a","b","c").map { Track("file:///$it.mp3",it,"Artist",45000,"Music") }
        context.getSharedPreferences("library",0).edit().putString("tracks",JSONArray(items.map { it.json() }).toString()).commit()
        library.setTracksHidden(setOf(items[0].uri),true);library.excludeTracks(setOf(items[1].uri))
        OnlineLibraryActions(context).set("hidden",JSONArray("""[{"id":"999"}]"""),true)
        val local=library.playableTracks().map { it.uri }.toSet();val hidden=OnlineLibraryActions(context).ids("hidden")
        val queue=listOf(items[0].uri,"afp-yandex://track/123",items[1].uri,items[2].uri,"afp-yandex://track/999","afp-yandex://track/123")
        assertEquals(listOf("afp-yandex://track/123",items[2].uri,"afp-yandex://track/123"),queue.filter { QueueEligibility.playable(it,local,hidden) })
        assertEquals(setOf(items[0].uri),library.hiddenTracks());assertEquals(setOf("999"),hidden)
    }
    @Test fun mixedQueueReorderAndRestartKeepOnlineMetadataWhilePruningUnavailableLocalTracks() {
        val prefs=context.getSharedPreferences("playback",0);prefs.edit().clear().commit()
        val items=listOf("a","b","c").map { Track("file:///$it.mp3",it,"Artist",45000,"Music") }
        context.getSharedPreferences("library",0).edit().clear().putString("tracks",JSONArray(items.map { it.json() }).toString()).commit()
        val store=OnlineLibraryStore(context);store.setSession("integration")
        val host=Robolectric.buildService(PlaybackService::class.java).create();val p=player(host.get())
        try {
            assertEquals(0,host.get().applyPlaybackCommand(PlaybackCommands.START_QUEUE,Bundle().apply { putString("source","");putString("uri",items[2].uri) }))
            p.pause()
            val rows=JSONArray((1..2).map { JSONObject().put("id",100+it).put("title","Online $it").put("artist","Y").put("duration",90000).put("cover","https://avatars.yandex.net/test/300x300") })
            assertEquals(0,host.get().applyPlaybackCommand(PlaybackCommands.START_ONLINE_QUEUE,Bundle().apply { putString("queueId",store.saveQueue(rows));putString("mode","append") }))
            p.seekTo(3,1234);val before=p.playWhenReady
            Library(context).setTracksHidden(setOf(items[0].uri),true);Library(context).excludeTracks(setOf(items[1].uri))
            // Older mixed queues can retain their local source; both forms must stay readable.
            prefs.edit().putString("queueSource","").commit()
            assertEquals(0,host.get().applyPlaybackCommand(PlaybackCommands.REORDER_QUEUE,Bundle().apply { putString("source","");putBoolean("refreshSource",true) }))
            assertEquals(listOf(items[2].uri,"afp-yandex://track/101","afp-yandex://track/102"),(0 until p.mediaItemCount).map { p.getMediaItemAt(it).mediaId })
            assertEquals("afp-yandex://track/101",p.currentMediaItem!!.mediaId);assertEquals(1234L,p.currentPosition);assertEquals(before,p.playWhenReady)
        } finally { host.destroy() }
        val restored=Robolectric.buildService(PlaybackService::class.java).create()
        awaitPlaybackRestore(restored.get())
        try {
            val q=player(restored.get());q.pause()
            assertEquals(listOf(items[2].uri,"afp-yandex://track/101","afp-yandex://track/102"),(0 until q.mediaItemCount).map { q.getMediaItemAt(it).mediaId })
            assertEquals("Online 1",q.getMediaItemAt(1).mediaMetadata.title.toString());assertEquals(90000L,q.getMediaItemAt(1).mediaMetadata.extras!!.getLong("onlineDuration"))
            assertEquals("afp-yandex://track/101",q.currentMediaItem!!.mediaId);assertEquals(1234L,q.currentPosition)
        } finally { restored.destroy() }
    }
    @Test fun mainPreferencesRemainIntactWhenOnlineSessionStarts() {
        val prefs=context.getSharedPreferences("library",0)
        prefs.edit().putString("roots","[\"content://provider/tree/Music\"]").putString("playlists","{\"P\":[\"file:///a.mp3\"]}")
            .putStringSet("hiddenTracks",setOf("file:///a.mp3")).putStringSet("excludedTracks",setOf("file:///b.mp3")).commit()
        val before=prefs.all.toMap()
        OnlineLibraryStore(context).setSession("new-production-account")
        OnlineLibraryActions(context).set("favorites",JSONArray("""[{"id":"123","title":"Online"}]"""),true)
        assertEquals(before,prefs.all)
    }
}
