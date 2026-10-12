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
