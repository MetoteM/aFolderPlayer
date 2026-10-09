package dev.alex.folderplayer

import android.content.Context
import android.os.Looper
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaController
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.net.URL
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class DirectLyricsTest {
 private fun field(a:MainActivity,name:String,value:Any) { MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.set(a,value) }
 private fun invoke(a:MainActivity,name:String,vararg args:Any) { MainActivity::class.java.getDeclaredMethod(name,*args.map { it.javaClass }.toTypedArray()).apply { isAccessible=true }.invoke(a,*args) }
 private fun await(condition:()->Boolean) {
  val end=System.currentTimeMillis()+8000
  while(!condition() && System.currentTimeMillis()<end) { shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10) }
  assertTrue(condition())
 }
 private fun run(cached:Boolean=false,status:Int=200,block:(MainActivity,Track,()->Int)->Unit) {
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val t=Track("file:///direct-$cached-$status.mp3","Cure","Metallica",294000,"Music/Load","Cure.mp3","Load")
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks",org.json.JSONArray(listOf(t.json())).toString()).commit()
  shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app,PlaybackService::class.java),android.os.Binder());shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
  val host=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=host.get()
  val player=ExoPlayer.Builder(a).build().apply { setMediaItem(t.item()) };val session=MediaSession.Builder(a,player).build()
  val future=MediaController.Builder(a,session.token).buildAsync();await { future.isDone };val controller=future.get();field(a,"controller",controller)
  var calls=0
  val body="""[{"id":1,"trackName":"Cure","artistName":"Metallica","albumName":"Load","duration":294,"plainLyrics":"Наш тестовый текст"}]"""
  val client=LyricsSearch { url -> calls++;object:HttpURLConnection(url) {
   override fun connect() {};override fun usingProxy()=false;override fun disconnect() {}
   override fun getResponseCode()=status;override fun getInputStream()=body.byteInputStream()
  } }
  field(a,"lyricsSearch\$delegate",lazy { client })
  if(cached)LyricsStore(a).save(t.uri,"Локальный текст")
  try { block(a,t,{calls}) } finally { host.pause().stop().destroy();controller.release();session.release();player.release() }
 }
 @Test fun textButtonSearchesAndSavesWithoutSearchDialogThenUsesOfflineCache() = run { a,t,calls ->
  invoke(a,"openLyrics");await { LyricsStore(a).read(t.uri)!=null }
  assertEquals("Наш тестовый текст",LyricsStore(a).read(t.uri));assertEquals(1,calls())
  invoke(a,"openLyrics");Thread.sleep(100);shadowOf(Looper.getMainLooper()).idle();assertEquals(1,calls())
 }
 @Test fun existingTextDoesNotOpenNetworkAndRestoringScreenDoesNotSearch() = run(cached=true) { a,t,calls ->
  invoke(a,"openLyrics");Thread.sleep(150);shadowOf(Looper.getMainLooper()).idle();assertEquals(0,calls());assertEquals("Локальный текст",LyricsStore(a).read(t.uri))
 }
 @Test fun server503DoesNotSaveOrLaunchAutomaticRetryLoop() = run(status=503) { a,t,calls ->
  invoke(a,"openLyrics");await { calls()==1 };Thread.sleep(150);shadowOf(Looper.getMainLooper()).idle()
  assertNull(LyricsStore(a).read(t.uri));assertEquals(1,calls())
 }
 @Test fun restoringMissingLyricsScreenDoesNotRequestInternet() = run { a,t,calls ->
  MainActivity::class.java.getDeclaredMethod("showPage",String::class.java,Boolean::class.javaPrimitiveType).apply { isAccessible=true }.invoke(a,"lyrics",false)
  Thread.sleep(150);shadowOf(Looper.getMainLooper()).idle();assertEquals(0,calls());assertNull(LyricsStore(a).read(t.uri))
 }

}
