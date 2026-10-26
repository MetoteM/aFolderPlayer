package dev.alex.folderplayer

import android.os.Looper
import java.time.Duration
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PowerSchedulingTest {
    @After fun resetRestore(){disableSyncRestore()}
    @Test
    fun pausedServiceDoesNotWritePeriodicallyAndSleepTimerStillFires() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("playback", 0)
        prefs.edit().clear().commit()
        val host = buildServiceSync()
        try {
            shadowOf(Looper.getMainLooper()).idle()
            val initial = prefs.all.toMap()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(60))
            assertEquals(initial, prefs.all)
            prefs.edit().putLong("sleepDeadline", System.currentTimeMillis() + 1000).commit()
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1001))
            assertFalse(prefs.contains("sleepDeadline"))
        } finally {
            host.destroy()
        }
    }

    @Test
    fun lightweightPositionSnapshotRestoresSameTrackWithoutChangingQueue() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("library", 0)
            .edit()
            .clear()
            .putString(
                "tracks",
                """[{"uri":"file:///resume.wav","title":"Resume","artist":"Test","duration":30000,"folder":""}]""",
            )
            .commit()
        val prefs = app.getSharedPreferences("playback", 0)
        prefs
            .edit()
            .clear()
            .putString("queue", "[\"file:///resume.wav\"]")
            .putInt("index", 0)
            .putLong("position", 70)
            .commit()
        app.getSharedPreferences("playback-position", 0)
            .edit()
            .clear()
            .putString("uri", "file:///resume.wav")
            .putInt("index", 0)
            .putLong("position", 3600)
            .commit()
        val host = buildServiceSync()
        try {
            val player =
                PlaybackService::class
                    .java
                    .getDeclaredField("player")
                    .apply { isAccessible = true }
                    .get(host.get()) as androidx.media3.common.Player
            assertEquals(3600L, player.currentPosition)
            assertEquals("file:///resume.wav", player.currentMediaItem?.mediaId)
        } finally {
            host.destroy()
        }
    }
}
