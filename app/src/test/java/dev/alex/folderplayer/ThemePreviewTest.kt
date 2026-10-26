package dev.alex.folderplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.json.JSONArray
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w360dp-h760dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemePreviewTest {
    @Test fun renderAllLibraryThemes(){
        val app=RuntimeEnvironment.getApplication()
        val tracks=listOf(
            Track("content://music/1","Northern Lights","Aurora",215000,"Music/Signals","01 Northern Lights.flac","Signals"),
            Track("content://music/2","Ocean Drive","Aurora",244000,"Music/Signals","02 Ocean Drive.flac","Signals"),
            Track("content://music/3","Golden Hour","Aurora",186000,"Music/Signals","03 Golden Hour.flac","Signals")
        )
        app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().putString("tracks",JSONArray(tracks.map { it.json() }).toString()).commit()
        val output=File("build/theme-previews").apply { mkdirs() }
        for(p in AppAppearance.choices){
            AppAppearance.save(app,p)
            val c=mainActivityController().create().start().also { awaitLibrary(it.get()) }
            try {
                val v=MainActivity::class.java.getDeclaredField("root").apply { isAccessible=true }.get(c.get()) as View
                v.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1520,View.MeasureSpec.EXACTLY));v.layout(0,0,720,1520)
                val image=Bitmap.createBitmap(720,1520,Bitmap.Config.ARGB_8888)
                v.draw(Canvas(image))
                File(output,"${p.id}.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) };image.recycle()
                val ui=MainActivity::class.java.getDeclaredField("screens").apply { isAccessible=true }.get(c.get()) as PlayerScreens
                ui.show("player")
                ui.update(PlayerScreens.State("preview","Northern Lights","Aurora","Signals · 2026","Signals",true,false,2,84000,215000,10))
                val screen=ui.view
                (screen.parent as? android.view.ViewGroup)?.removeView(screen)
                c.get().setContentView(screen)
                screen.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1520,View.MeasureSpec.EXACTLY));screen.layout(0,0,720,1520)
                val player=Bitmap.createBitmap(720,1520,Bitmap.Config.ARGB_8888);screen.draw(Canvas(player))
                File(output,"${p.id}-player.png").outputStream().use { player.compress(Bitmap.CompressFormat.PNG,100,it) };player.recycle()

            }finally { c.stop().destroy() }
        }
    }
}
