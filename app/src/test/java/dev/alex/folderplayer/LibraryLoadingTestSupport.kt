package dev.alex.folderplayer

import android.os.Looper
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertFalse
import org.robolectric.Shadows.shadowOf

internal fun awaitLibrary(activity: MainActivity) {
    val field =
        MainActivity::class.java.getDeclaredField("libraryLoading").apply { isAccessible = true }
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
    while (field.getBoolean(activity) && System.nanoTime() < deadline) {
        shadowOf(Looper.getMainLooper()).idle()
        Thread.yield()
    }
    assertFalse("Library loading timed out", field.getBoolean(activity))
}
