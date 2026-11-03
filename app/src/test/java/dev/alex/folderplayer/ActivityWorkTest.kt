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
@Config(sdk=[28],manifest=Config.NONE)
class ActivityWorkTest {
    @Test fun deliveryUsesMainThreadAndWorkUsesApplicationContext() {
        val app=RuntimeEnvironment.getApplication()
        val owner=ActivityWork(app)
        val finished=CountDownLatch(1)
        var received: String?=null
        try {
            owner.submit({ context ->
                assertSame(app,context)
                assertNotEquals(Looper.getMainLooper(),Looper.myLooper())
                finished.countDown()
                "ready"
            }) { result -> assertEquals(Looper.getMainLooper(),Looper.myLooper());received=result.getOrThrow() }
            assertTrue(finished.await(5,TimeUnit.SECONDS))
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while(received==null && System.nanoTime()<deadline) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
            assertEquals("ready",received)
        } finally { owner.close() }
    }

    @Test fun closeDropsCallbacksEvenWhenProviderIgnoresInterruption() {
        val owner=ActivityWork(RuntimeEnvironment.getApplication())
        val started=CountDownLatch(1);val release=CountDownLatch(1);val done=CountDownLatch(1)
        var delivered=false
        try {
            owner.submit({ _ ->
                started.countDown()
                while(release.count>0) { try { release.await() } catch(_:InterruptedException) {} }
                done.countDown()
                "late"
            }) { delivered=true }
            assertTrue(started.await(5,TimeUnit.SECONDS))
            owner.close();release.countDown()
            assertTrue(done.await(5,TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(delivered)
        } finally { release.countDown();owner.close() }
    }
}
