package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class MainScanLifecycleTest {
    private fun field(host: MainActivity, name: String): Any? = MainActivity::class.java
        .getDeclaredField(name).apply { isAccessible=true }.get(host)

    @Test fun rotatingWhileScanningKeepsWorkAndOnlyUpdatesTheNewScreen() {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("library",0).edit().clear().commit()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        val old=screen.get()
        @Suppress("UNCHECKED_CAST")
        val work=field(old,"scanWork") as RetainedOperation<Library.ScanResult>
        val entered=CountDownLatch(1);val release=CountDownLatch(1)
        val expected=Track("a","After scan","",30000,"")
        try {
            work.start("library") { _,_ -> entered.countDown();release.await();Library.ScanResult(listOf(expected),0,0) }
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            screen.recreate()
            val current=screen.get()
            assertNotSame(old,current)
            assertSame(work,field(current,"scanWork"))
            assertEquals(true,field(current,"scanning"))
            release.countDown()
            val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5)
            while ((field(current,"tracks") as List<*>).isEmpty() && System.nanoTime()<deadline) {
                shadowOf(Looper.getMainLooper()).idle();Thread.yield()
            }
            assertEquals(listOf(expected),field(current,"tracks"))
            assertTrue((field(old,"tracks") as List<*>).isEmpty())
            assertEquals(false,field(current,"scanning"))
        } finally {
            release.countDown()
            screen.pause().stop().destroy()
        }
    }
}
