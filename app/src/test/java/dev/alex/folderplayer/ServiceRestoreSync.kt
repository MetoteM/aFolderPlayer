package dev.alex.folderplayer

import android.os.Looper
import java.util.concurrent.Executor
import org.robolectric.Robolectric
import org.robolectric.android.controller.ServiceController
import org.robolectric.Shadows

/**
 * Deterministic PlaybackService creation for Robolectric tests.
 *
 * Production restore runs on a background executor and posts player mutations
 * back to the main looper. Under Robolectric that is a thread race: tests
 * asserted immediately after create() while restore was still running. This
 * helper swaps in a synchronous executor before create() and flushes the main
 * looper afterwards, so by the time it returns the restored state is fully
 * applied.
 */
internal fun buildServiceSync(): ServiceController<PlaybackService> {
    PlaybackService.restoreExecutorOverride = directExecutor
    val host = Robolectric.buildService(PlaybackService::class.java).create()
    Shadows.shadowOf(Looper.getMainLooper()).idle()
    return host
}

internal val directExecutor: Executor = Executor { it.run() }

/** Enable synchronous service restore; pair with [disableSyncRestore] in @After. */
internal fun enableSyncRestore() {
    PlaybackService.restoreExecutorOverride = directExecutor
}

internal fun disableSyncRestore() {
    PlaybackService.restoreExecutorOverride = null
}
