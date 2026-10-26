package dev.alex.folderplayer

import android.content.ComponentName
import android.os.Binder
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
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
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class LandscapeBrowserTest {
    private fun field(host:MainActivity,name:String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(host)
    private fun all(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    @Test fun phoneAndTabletLandscapeKeepActualLibraryListVisibleEvenDuringSelection() {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        app.getSharedPreferences("library",0).edit().clear().putString("tracks",org.json.JSONArray((1..20).map {
            Track("file:///$it.mp3","Композиция $it","Исполнитель",180000,"Music").json()
        }).toString()).commit()
        val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().also { awaitLibrary(it.get()) }.resume()
        try {
            val host=screen.get();val root=field(host,"root") as View
            val results=(field(host,"list") as View).parent as ScrollView
            for(size in listOf(640 to 280,740 to 312,1024 to 600,360 to 640,640 to 280)) {
                repeat(4) {
                    root.measure(View.MeasureSpec.makeMeasureSpec(size.first,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(size.second,View.MeasureSpec.EXACTLY))
                    root.layout(0,0,size.first,size.second)
                }
                assertTrue("List collapsed in $size: ${results.height}",results.height >= if(size.first>size.second)90 else 1)
                if(size.first>size.second) {
                    assertTrue(results.width<root.width)
                    assertTrue(all(root).filterIsInstance<ScrollView>().size>=2)
                }
                if(size.first>size.second) {
                    val image=android.graphics.Bitmap.createBitmap(size.first,size.second,android.graphics.Bitmap.Config.ARGB_8888)
                    root.draw(android.graphics.Canvas(image))
                    val target=java.io.File("../qa/rotation-landscape/library-${size.first}x${size.second}.png")
                    target.parentFile!!.mkdirs();target.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
                }
                (field(host,"selectionButton") as View).performClick()
            }
        } finally { screen.pause().stop().destroy() }
    }
}
