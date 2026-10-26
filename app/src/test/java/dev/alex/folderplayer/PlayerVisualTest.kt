package dev.alex.folderplayer

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],qualifiers="w411dp-h891dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PlayerVisualTest {
 @Test fun renderThreeScreensFromActualViews() {
  val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
  val ui=PlayerScreens(activity,object:PlayerScreens.Actions {
   override fun back(){};override fun open(page:String){};override fun toggle(){};override fun previous(){};override fun next(){};override fun skip(direction:Int){};override fun seek(progress:Int){};override fun favorite(){};override fun playlists(){};override fun repeat(){};override fun sound(){};override fun lyricsMenu(){};override fun select(index:Int){};override fun move(from:Int,to:Int){}
  })
  val art=Bitmap.createBitmap(600,600,Bitmap.Config.ARGB_8888);val canvas=Canvas(art);canvas.drawColor(Color.rgb(26,48,54));val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=PlayerScreens.accent };canvas.drawCircle(390f,190f,100f,paint);paint.color=Color.rgb(12,27,31);val path=android.graphics.Path().apply { moveTo(0f,600f);lineTo(0f,460f);lineTo(180f,260f);lineTo(320f,430f);lineTo(420f,320f);lineTo(600f,480f);lineTo(600f,600f);close() };canvas.drawPath(path,paint)
  for(page in listOf("player","lyrics","translation","queue","sound")) {
   ui.show(page);ui.update(PlayerScreens.State("a","За кольцом кольцо","fantasticmodern109","Наши песни · 2026","Наши песни",true,false,2,84000,211000,10));ui.setArtwork("a",art)
   ui.setLyrics("a",LyricsDocument("[00:00.000]Мы идём навстречу свету\n[00:40.000]По дороге за мечтой\n[01:20.000]За кольцом кольцо — и снова\n[01:40.000]Этот мир зовёт с собой"),null)
   ui.setTranslation("a","Мы вместе идём под звёздами.\n\nСолнце снова взойдёт.\nЯ люблю музыку.","Машинный перевод · английский → русский · сохранён на устройстве")
   ui.setQueue(listOf(PlayerScreens.Entry("a","За кольцом кольцо","fantasticmodern109",211000),PlayerScreens.Entry("b","Соник, лети","fantasticmodern109",207000),PlayerScreens.Entry("c","ёжик лети!","fantasticmodern109",207000)))
   activity.setContentView(ui.view);ui.view.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));ui.view.layout(0,0,822,1782)
   val image=Bitmap.createBitmap(822,1782,Bitmap.Config.ARGB_8888);ui.view.draw(Canvas(image))
   val target=File("../qa/ui-0.5.0/$page.png");target.parentFile!!.mkdirs();target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
  }
 }
 @Test fun renderPlaylistInCommonTheme() {
  val app=org.robolectric.RuntimeEnvironment.getApplication();val items=(1..7).map { Track("file:///$it.mp3","Песня номер $it","Metallica",294000,"Music/Load","$it.mp3","Load","Metallica",1996,it) }
  app.getSharedPreferences("playback",android.content.Context.MODE_PRIVATE).edit().clear().commit()
  app.getSharedPreferences("library",android.content.Context.MODE_PRIVATE).edit().clear().putString("tracks",org.json.JSONArray(items.map { it.json() }).toString()).putString("playlists",org.json.JSONObject().put("Метла",org.json.JSONArray(items.map { it.uri })).toString()).commit()
  val host=mainActivityController().create().start().also { awaitLibrary(it.get()) };val activity=host.get()
  try {
   MainActivity::class.java.getDeclaredField("selectedPlaylist").apply { isAccessible=true }.set(activity,"Метла");MainActivity::class.java.getDeclaredMethod("render").apply { isAccessible=true }.invoke(activity)
   val root=MainActivity::class.java.getDeclaredField("root").apply { isAccessible=true }.get(activity) as View
   root.measure(View.MeasureSpec.makeMeasureSpec(822,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1782,View.MeasureSpec.EXACTLY));root.layout(0,0,822,1782)
   val image=Bitmap.createBitmap(822,1782,Bitmap.Config.ARGB_8888);root.draw(Canvas(image));val target=File("../qa/ui-0.5.0/playlist.png");target.parentFile!!.mkdirs();target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
  } finally { host.stop().destroy() }
 }

 @Test fun shortLandscapeKeepsArtworkAndControlsAtLargeFont() {
  val host=Robolectric.buildActivity(Activity::class.java).setup()
  try {
   val context=host.get().createConfigurationContext(android.content.res.Configuration(host.get().resources.configuration).apply { fontScale=1.6f })
   val ui=PlayerScreens(context,object:PlayerScreens.Actions {
    override fun back(){};override fun open(page:String){};override fun toggle(){};override fun previous(){};override fun next(){};override fun skip(direction:Int){};override fun seek(progress:Int){};override fun favorite(){};override fun playlists(){};override fun repeat(){};override fun sound(){};override fun lyricsMenu(){};override fun select(index:Int){};override fun move(from:Int,to:Int){}
   })
   ui.show("player");ui.update(PlayerScreens.State("a","Очень длинное название композиции","Исполнитель","Альбом","Music",false,false,0,84000,211000,10))
   val density=context.resources.displayMetrics.density
   val width=(640*density).toInt();val height=(264*density).toInt()
   repeat(4) { ui.view.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));ui.view.layout(0,0,width,height) }
   fun all(v:View):List<View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
   val cover=all(ui.view).filterIsInstance<android.widget.ImageView>().single()
   org.junit.Assert.assertTrue("Artwork disappeared",cover.height>40*density)
   val controls=all(ui.view).filterIsInstance<android.widget.ScrollView>().single()
   org.junit.Assert.assertTrue(controls.height>0)
   val queue=all(controls).filterIsInstance<android.widget.Button>().single { it.text=="Очередь" }
   org.junit.Assert.assertEquals("Queue label must not split into fragments",1,queue.lineCount)
   val play=all(controls).single { it.contentDescription=="Воспроизведение / пауза" }
   val rect=android.graphics.Rect();play.getDrawingRect(rect);ui.view.offsetDescendantRectToMyCoords(play,rect)
   org.junit.Assert.assertTrue("Primary transport clipped: $rect",rect.top>=0 && rect.bottom<=height)
   val image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);ui.view.draw(Canvas(image))
   val target=File("../qa/rotation-landscape/player-large-font.png");target.parentFile!!.mkdirs();target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
  } finally { host.pause().stop().destroy() }
 }

}
