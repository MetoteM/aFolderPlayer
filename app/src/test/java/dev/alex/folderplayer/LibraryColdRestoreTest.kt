package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.ScrollView
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="mdpi")
class LibraryColdRestoreTest {
    private fun waitForLibrary(host:MainActivity) {
        val end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
        while(host.libraryLoading && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.yield() }
        assertFalse(host.libraryLoading)
        assertNull(host.libraryLoadError)
    }
    private fun layout(host:MainActivity) {
        repeat(3) {
            host.root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(640,View.MeasureSpec.EXACTLY))
            host.root.layout(0,0,360,640)
        }
        host.root.viewTreeObserver.dispatchOnGlobalLayout()
    }
    @Test fun pageAndScrollWaitForAsynchronousLibraryBeforeRestoration() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        app.getSharedPreferences("playback",0).edit().clear().commit()
        app.getSharedPreferences("library",0).edit().clear().putString("tracks",JSONArray((1..1000).map { Track("file:///$it.mp3","Song $it","Artist",40000,"Music").json() }).toString()).commit()
        val saved=Bundle()
        val first=Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        try {
            waitForLibrary(first.get())
            first.get().libraryPage=2;first.get().render()
            shadowOf(Looper.getMainLooper()).idle();layout(first.get())
            val scroll=first.get().list.parent as ScrollView
            scroll.scrollTo(0,640);assertEquals(640,scroll.scrollY)
            first.saveInstanceState(saved)
        } finally { first.pause().stop().destroy() }
        val second=Robolectric.buildActivity(MainActivity::class.java).create(saved).start().resume().visible()
        try {
            waitForLibrary(second.get());layout(second.get());shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2,second.get().libraryPage)
            assertEquals(640,(second.get().list.parent as ScrollView).scrollY)
        } finally { second.pause().stop().destroy() }
    }
}
