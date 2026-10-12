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
}
