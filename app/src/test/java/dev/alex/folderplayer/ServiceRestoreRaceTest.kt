package dev.alex.folderplayer

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import java.util.concurrent.Executor

/**
 * queueGeneration guards the main-thread application of the restored state.
 * Semantics (0.9.11): only queue-REPLACING commands (START_BOOK/START_QUEUE/
 * START_ONLINE_QUEUE/REORDER_QUEUE) invalidate an in-flight restore. Playback
 * settings such as SET_REPEAT must NOT: invalidating on them discarded the
 * restored queue without any replacement, killing playback after the first
 * track change. Instead, the restore re-reads requestedRepeat at apply time,
 * so the user's repeat choice survives alongside the queue.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ServiceRestoreRaceTest {
    private var runTask: Runnable? = null

    @After
    fun tearDown() {
        PlaybackService.restoreExecutorOverride = null
    }

    /** Service whose restore task is captured but NOT executed. */
    private fun serviceWithPendingRestore(): ServiceController<PlaybackService> {
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("playback", Context.MODE_PRIVATE)
            .edit().putInt("requestedRepeat", Player.REPEAT_MODE_ONE).apply()
        PlaybackService.restoreExecutorOverride = Executor { runTask = it }
        return Robolectric.buildService(PlaybackService::class.java).create()
    }

    @Test
    fun repeatDuringRestoreDoesNotDiscardRestoredQueue() {
        val controller = serviceWithPendingRestore()
        val service = controller.get()
        val result =
            service.applyPlaybackCommand(
                PlaybackCommands.SET_REPEAT,
                Bundle().apply { putInt("mode", 2) },
            )
        assertEquals(SessionResult.RESULT_SUCCESS, result)
        assertEquals(2, service.player.repeatMode)
        // Saved state recorded AFTER the command: shuffle=true marks the queued
        // restore so that its application is observable (a SET_REPEAT-driven
        // saveState() would otherwise rewrite the flag mid-test).
        service.getSharedPreferences("playback", Context.MODE_PRIVATE)
            .edit().putBoolean("shuffle", true).apply()
        // Restore finishes AFTER the repeat change — it must still apply, and
        // the user's repeat choice must survive the apply.
        runTask!!.run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "restore must still apply after a repeat command (queue is not discarded)",
            true,
            service.player.shuffleModeEnabled,
        )
        assertEquals(
            "repeat set during restore must survive the restore apply",
            2,
            service.player.repeatMode,
        )
        controller.destroy()
    }

    @Test
    fun rejectedQueueCommandDoesNotDiscardRestore() {
        val controller = serviceWithPendingRestore()
        val service = controller.get()
        // Validation failure creates no replacement queue; saved state must survive.
        val result =
            service.applyPlaybackCommand(
                PlaybackCommands.START_QUEUE,
                Bundle().apply { putString("source", ""); putString("uri", "no/such/track") },
            )
        assertEquals(SessionError.ERROR_BAD_VALUE, result)
        service.getSharedPreferences("playback", Context.MODE_PRIVATE)
            .edit().putBoolean("shuffle", true).apply()
        runTask!!.run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "restore still applies after a rejected command",
            true,
            service.player.shuffleModeEnabled,
        )
        controller.destroy()
    }

    @Test
    fun restoreAppliesWhenNoCommandInterferes() {
        val controller = serviceWithPendingRestore()
        runTask!!.run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "restore without competing commands still applies saved state",
            Player.REPEAT_MODE_ONE,
            controller.get().player.repeatMode,
        )
        controller.destroy()
    }
    @Test fun acceptedQueueSurvivesAlreadyReadRestoreResult() {
        val app=RuntimeEnvironment.getApplication()
        LibraryDatabase.resetForTests(app)
        val tracks=listOf(Track("file:///old.mp3","Old","Artist",40000,"Music"),Track("file:///new.mp3","New","Artist",40000,"Music"))
        app.getSharedPreferences("library",0).edit().clear().putString("tracks",org.json.JSONArray(tracks.map { it.json() }).toString()).commit()
        app.getSharedPreferences("playback",0).edit().clear().putString("queue","[\"file:///old.mp3\"]").commit()
        val host=serviceWithPendingRestore()
        try {
            // Read the old snapshot on a worker, but do not deliver its main-thread post yet.
            val reader=Thread { runTask!!.run() };reader.start();reader.join(5000)
            org.junit.Assert.assertFalse(reader.isAlive)
            assertEquals(SessionResult.RESULT_SUCCESS,host.get().applyPlaybackCommand(PlaybackCommands.START_QUEUE,
                Bundle().apply { putString("source","");putString("uri","file:///new.mp3") }))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals("file:///new.mp3",host.get().player.currentMediaItem!!.mediaId)
        } finally { host.destroy();LibraryDatabase.resetForTests(app) }
    }

    @Test fun destroyingBeforeRestoreDoesNotEraseSavedQueue() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("playback",0).edit().clear().putString("queue","[\"saved\"]").commit()
        val host=serviceWithPendingRestore()
        host.destroy()
        assertEquals("[\"saved\"]",app.getSharedPreferences("playback",0).getString("queue",null))
    }

}
