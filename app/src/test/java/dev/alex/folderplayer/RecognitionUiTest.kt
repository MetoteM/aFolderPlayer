package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class RecognitionUiTest {
 @Test fun leavingCompletedLyricsSearchKeepsChoicesButChangingTrackClearsThem() {
  val app=org.robolectric.RuntimeEnvironment.getApplication();org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app,PlaybackService::class.java),android.os.Binder());org.robolectric.Shadows.shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
  val host=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=host.get()
  val id=MainActivity::class.java.getDeclaredField("directId").apply { isAccessible=true };val results=MainActivity::class.java.getDeclaredField("directResults").apply { isAccessible=true }
  val found=LyricsSearch.parse("""[{"id":1,"trackName":"Song","artistName":"Artist","duration":100,"plainLyrics":"Words"}]""",100000)
  id.set(activity,"song");results.set(activity,found)
  activity.stopDirectLyrics(false)
  assertEquals("song",id.get(activity));assertEquals(found,results.get(activity));activity.stopDirectLyrics(true);assertNull(id.get(activity));assertTrue((results.get(activity) as List<*>).isEmpty())
  host.pause().stop().destroy()
 }
 @Test fun recognitionIsOnMainScreenAndRemovedFromSettings() {
  val app=org.robolectric.RuntimeEnvironment.getApplication();app.getSharedPreferences("playback",0).edit().clear().commit()
  org.robolectric.Shadows.shadowOf(app).setComponentNameAndServiceForBindService(android.content.ComponentName(app,PlaybackService::class.java),android.os.Binder())
  org.robolectric.Shadows.shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
  val host=Robolectric.buildActivity(MainActivity::class.java).setup();val activity=host.get()
  fun views(v:android.view.View):List<android.view.View> = listOf(v)+if(v is android.view.ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) }else emptyList()
  val button=views(activity.window.decorView).filterIsInstance<android.widget.Button>().first { it.text=="Определить песню" };button.performClick()
  assertEquals(RecognitionActivity::class.java.name,org.robolectric.Shadows.shadowOf(activity).nextStartedActivity.component!!.className)
  MainActivity::class.java.getDeclaredMethod("settings").apply { isAccessible=true }.invoke(activity)
  val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();assertFalse((0 until dialog.listView.adapter.count).any { dialog.listView.adapter.getItem(it).toString()=="Определить песню" });dialog.dismiss()
  val screens=MainActivity::class.java.getDeclaredField("screens").apply { isAccessible=true }.get(activity) as PlayerScreens;screens.show("player")
  assertTrue(views(screens.view).filterIsInstance<android.widget.Button>().any { it.text=="Определить песню" })
  host.pause().stop().destroy()
 }
 @Test fun childCloseKeepsParentMenuAndAnotherChoiceWorks(){
  val host=Robolectric.buildActivity(Activity::class.java).setup()
  val activity=host.get();var child:AlertDialog?=null;var selections=0
  val parent=optionMenu(activity,"Настройки",listOf("Одна","Другая")){selections++;child=AlertDialog.Builder(activity).setMessage("Опция").setPositiveButton("Закрыть",null).show()}
  parent.listView.performItemClick(null,0,0)
  assertTrue(parent.isShowing);assertTrue(child!!.isShowing)
  child!!.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
  assertTrue(parent.isShowing)
  parent.listView.performItemClick(null,1,1);assertEquals(2,selections)
  child!!.dismiss();parent.dismiss();host.pause().stop().destroy()
 }
 @Test fun recognitionDoesNotStartMicrophoneOnOpen(){
  val host=Robolectric.buildActivity(RecognitionActivity::class.java).setup()
  val activity=host.get()
  val field=RecognitionActivity::class.java.getDeclaredField("recorder").apply { isAccessible=true }
  assertNull(field.get(activity));host.pause().stop().destroy();assertNull(field.get(activity))
 }
 @Test fun foundLyricsRemainOnScreenWithoutChoiceDialog(){
  val host=Robolectric.buildActivity(RecognitionActivity::class.java).setup();val activity=host.get()
  RecognitionActivity::class.java.getDeclaredMethod("showResult",String::class.java,String::class.java).apply { isAccessible=true }.invoke(activity,"Ghost Riders in the Sky","Legendary Shack Shakers")
  val rows=LyricsSearch.parse("""[{"id":1,"trackName":"Ghost Riders in the Sky","artistName":"Other singer","plainLyrics":"Sample words"}]""",0)
  RecognitionActivity::class.java.declaredMethods.first { it.name=="showLyrics" }.apply { isAccessible=true }.invoke(activity,rows,"Legendary Shack Shakers")
  val field=RecognitionActivity::class.java.getDeclaredField("lyricsChoices").apply { isAccessible=true }
  val choices=field.get(activity) as android.widget.LinearLayout
  assertEquals(1,choices.childCount);val choice=choices.getChildAt(0) as android.widget.Button
  assertTrue(choice.text.contains("Другая версия"));assertTrue(choice.text.contains("Other singer"))
  host.pause().stop().start().resume();assertEquals(1,choices.childCount)
  host.pause().stop().destroy()
 }
}
