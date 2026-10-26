package dev.alex.folderplayer

import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

internal fun awaitPlaybackRestore(service: PlaybackService) {
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
    while (service.restoring && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.yield()
    }
    assertFalse("Playback restoration timed out", service.restoring)
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class PlaybackRestoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("playback", 0)
    private fun prepare() {
        prefs.edit().clear().putString("queue", "[\"file:///old.wav\"]").putLong("position", 12000).commit()
        app.getSharedPreferences("playback-position", 0).edit().clear().commit()
        app.getSharedPreferences("library", 0).edit().clear().putString("tracks", """[
          {"uri":"file:///old.wav","title":"Old","artist":"","duration":30000,"folder":""},
          {"uri":"file:///new.wav","title":"New","artist":"","duration":30000,"folder":""}]
        """).commit()
    }
    private fun player(service: PlaybackService) = PlaybackService::class.java.getDeclaredField("player")
        .apply { isAccessible=true }.get(service) as Player

    @Test fun creationDoesNotEraseSavedQueueBeforeAsyncRestoreAndDestroyPreservesIt() {
        prepare()
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        assertEquals("[\"file:///old.wav\"]", prefs.getString("queue", null))
        host.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("[\"file:///old.wav\"]", prefs.getString("queue", null))
        assertEquals(12000L, prefs.getLong("position", 0))
    }
    @Test fun queueAndPositionRestoreOnPlayerLooper() {
        prepare()
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        try {
            awaitPlaybackRestore(host.get())
            assertEquals("file:///old.wav", player(host.get()).currentMediaItem!!.mediaId)
            assertEquals(12000L, player(host.get()).currentPosition)
            assertFalse(player(host.get()).playWhenReady)
        } finally { host.destroy() }
    }
    @Test fun explicitPlaybackWinsOverStartupRestoration() {
        prepare()
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        try {
            host.get().applyPlaybackCommand(PlaybackCommands.START_QUEUE, Bundle().apply { putString("uri", "file:///new.wav") })
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(host.get().restoring)
            assertEquals("file:///new.wav", player(host.get()).currentMediaItem!!.mediaId)
            assertEquals(0L, player(host.get()).currentPosition)
        } finally { host.destroy() }
    }
    @Test fun missingBookFallsBackToSavedLocalQueue() {
        prepare()
        prefs.edit().putString("activeBook", "removed-book").commit()
        val saved=PlaybackRestore.load(app)
        assertNull(saved.book)
        assertEquals(listOf("file:///old.wav"),saved.items.map { it.mediaId })
    }
    @Test fun emptyQueueDoesNotLoadLocalOrOnlineLibrary() {
        prepare()
        prefs.edit().putString("queue", "[]").commit()
        val context=object:android.content.ContextWrapper(app) {
            override fun getSharedPreferences(name:String,mode:Int):android.content.SharedPreferences {
                check(name!="library" && name!="online_actions") { "Unneeded library read" }
                return super.getSharedPreferences(name,mode)
            }
        }
        assertTrue(PlaybackRestore.load(context).items.isEmpty())
    }

}
