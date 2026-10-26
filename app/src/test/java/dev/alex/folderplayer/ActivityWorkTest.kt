package dev.alex.folderplayer

import android.os.Looper
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
class ActivityWorkTest {
    @Test
    fun workRunsOffMainWithApplicationContextAndDeliversOnMain() {
        val app = RuntimeEnvironment.getApplication()
        val owner = ActivityWork(app)
        val done = CountDownLatch(1)
        var received: String? = null
        try {
            owner.submit({ context ->
                assertSame(app, context)
                assertNotEquals(Looper.getMainLooper().thread, Thread.currentThread())
                done.countDown()
                "ready"
            }) { result ->
                assertEquals(Looper.getMainLooper(), Looper.myLooper())
                received = result.getOrThrow()
            }
            assertTrue(done.await(2, TimeUnit.SECONDS))
            val limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (received == null && System.nanoTime() < limit) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.yield()
            }
            assertEquals("ready", received)
        } finally {
            owner.close()
        }
    }

    @Test
    fun closingDropsResultsEvenWhenProviderIgnoresInterruptionAndCancelsQueuedWork() {
        val owner = ActivityWork(RuntimeEnvironment.getApplication())
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        var delivered = 0
        val queued = java.util.concurrent.atomic.AtomicInteger()
        owner.submit({ _ ->
            started.countDown()
            try {
                finish.await()
            } catch (_: InterruptedException) {
                finish.await()
            }
            stopped.countDown()
            "late"
        }) {
            delivered++
        }
        assertTrue(started.await(2, TimeUnit.SECONDS))
        owner.submit({ _ -> queued.incrementAndGet() }) { delivered++ }
        owner.close()
        finish.countDown()
        assertTrue(stopped.await(2, TimeUnit.SECONDS))
        repeat(5) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.yield()
        }
        assertEquals(0, delivered)
        assertEquals(0, queued.get())
    }
}
