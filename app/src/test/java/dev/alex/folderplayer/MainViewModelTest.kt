package dev.alex.folderplayer

import android.os.Looper
import androidx.lifecycle.ViewModelStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class MainViewModelTest {
    private fun await(condition: () -> Boolean) {
        val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition() && System.nanoTime() < limit) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertTrue(condition())
    }

    @Test
    fun coldReadCannotBlockMainAndClearedOwnerCannotPublishItsLateResult() {
        val app = RuntimeEnvironment.getApplication()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val model =
            MainViewModel(app) {
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                entered.countDown()
                release.await()
                finished.countDown()
                listOf(Track("a", "A", "", 30000, ""))
            }
        val store = ViewModelStore()
        store.put("main", model)
        try {
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertTrue(model.libraryState.value.loading)
            store.clear()
            release.countDown()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            repeat(5) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertTrue(model.libraryState.value.tracks.isEmpty())
        } finally {
            release.countDown()
            store.clear()
        }
    }

    @Test
    fun reloadFailureKeepsLastLibraryAndRetryRecovers() {
        var fail = false
        val expected = listOf(Track("a", "A", "", 30000, ""))
        val model =
            MainViewModel(RuntimeEnvironment.getApplication()) {
                if (fail) error("Disk read failed") else expected
            }
        val store = ViewModelStore()
        store.put("main", model)
        try {
            await { !model.libraryState.value.loading }
            assertEquals(expected, model.libraryState.value.tracks)
            fail = true
            model.reload()
            await { !model.libraryState.value.loading }
            assertNotNull(model.libraryState.value.error)
            assertEquals(expected, model.libraryState.value.tracks)
            fail = false
            model.reload()
            await { !model.libraryState.value.loading }
            assertNull(model.libraryState.value.error)
            assertEquals(expected, model.libraryState.value.tracks)
        } finally {
            store.clear()
        }
    }
}
