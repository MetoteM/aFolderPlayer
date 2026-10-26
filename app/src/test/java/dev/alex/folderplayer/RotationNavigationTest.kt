package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RotationNavigationTest {
    private fun field(host:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(host)
    private fun stub() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
    }
    @Test fun everyPlayerPageSurvivesRecreation() {
        stub()
        for(page in listOf("player","lyrics","translation","queue","sound")) {
            val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
            try {
                MainActivity::class.java.getDeclaredMethod("showPage",String::class.java,Boolean::class.javaPrimitiveType)
                    .apply { isAccessible=true }.invoke(screen.get(),page,false)
                screen.recreate()
                assertEquals(page,field(screen.get(),"page"))
                assertEquals(android.view.View.VISIBLE,(field(screen.get(),"screens") as PlayerScreens).view.visibility)
            } finally { screen.pause().stop().destroy() }
        }
    }
    @Test fun rotationKeepsBrowsingPlaylistQueryAndSelectionInsteadOfPlayingSource() {
        stub()
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("library",0).edit().clear().putString("playlists","{\"Playing\":[\"a\"],\"Browsing\":[\"b\"]}").commit()
        app.getSharedPreferences("playback",0).edit().clear().putString("queue","[\"a\"]").putString("queueSource","Playing").commit()
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume()
        try {
            MainActivity::class.java.getDeclaredField("selectedPlaylist").apply { isAccessible=true }.set(screen.get(),"Browsing")
            MainActivity::class.java.getDeclaredField("selecting").apply { isAccessible=true }.setBoolean(screen.get(),true)
            @Suppress("UNCHECKED_CAST") val selection=field(screen.get(),"selection") as MutableSet<String>
            selection.add("b")
            (field(screen.get(),"search") as EditText).setText("my search")
            screen.recreate()
            assertEquals("Browsing",field(screen.get(),"selectedPlaylist"))
            assertEquals("my search",(field(screen.get(),"search") as EditText).text.toString())
            assertEquals(setOf("b"),field(screen.get(),"selection"))
            assertEquals(true,field(screen.get(),"selecting"))
        } finally { screen.pause().stop().destroy() }
    }
}
