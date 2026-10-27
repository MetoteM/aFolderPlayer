package dev.alex.folderplayer

import android.content.Context
import android.os.Bundle
import android.os.Looper
import androidx.media3.common.Player
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
 * The async restore used to overwrite a queue a user had already changed while
 * the restore was still scanning the library. queueGeneration now guards the
 * main-thread application of the restored state.
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
    fun commandDuringRestorePreventsStaleRestore() {
        val controller = serviceWithPendingRestore()
        val service = controller.get()
        val result =
            service.applyPlaybackCommand(
                PlaybackCommands.SET_REPEAT,
                Bundle().apply { putInt("mode", 2) },
            )
        assertEquals(SessionResult.RESULT_SUCCESS, result)
        assertEquals(2, service.player.repeatMode)
        // Restore finishes AFTER the user command — it must not apply.
        runTask!!.run()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "restore must not overwrite a command issued during restore",
            2,
            service.player.repeatMode,
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
}
