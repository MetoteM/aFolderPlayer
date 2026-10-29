package dev.alex.folderplayer

import android.os.Looper
import android.widget.LinearLayout
import android.widget.EditText
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LibraryPagingTest {
    @Test fun largeLibraryBuildsOnlyOnePageAndSearchStillFindsEntriesOutsideIt(){
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("playback",0).edit().clear().commit()
        app.getSharedPreferences("library",0).edit().clear().putString("tracks",JSONArray((1..1200).map {
            JSONObject().put("uri","file:///$it.mp3").put("title","Track %04d".format(it)).put("artist","").put("duration",30000).put("folder","")
        }).toString()).commit()
        shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app,PlaybackService::class.java),android.os.Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start()
        try {
            val a=screen.get()
            fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(a)
            // Library now loads off the main thread; wait for the delivery post.
            val end=System.currentTimeMillis()+10000
            while((field("visible") as List<*>).isEmpty() && System.currentTimeMillis()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
            assertTrue((field("list") as LinearLayout).childCount<=203)
            assertEquals(1200,(field("visible") as List<*>).size)
            (field("search") as EditText).setText("1200")
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200))
            assertEquals(1,(field("visible") as List<*>).size)
            assertEquals("Track 1200",((field("visible") as List<*>).single() as Track).title)
        } finally { screen.stop().destroy() }
    }
}
