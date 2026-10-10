package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
import org.robolectric.shadows.ShadowAlertDialog
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[26])
class DocumentActivityTest {
 private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup)(0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
 private fun button(activity:DocumentActivity,text:String)=views(activity.window.decorView).filterIsInstance<Button>().single { it.text.toString().startsWith(text) }
 @Test fun restoreAndSaveAddsHistoryAndUpdatesSongWithoutEngine() {
  val app=RuntimeEnvironment.getApplication();val file=File(app.filesDir,"full-document-history.json")
  val row=JSONObject().put("id","saved").put("source","Hello.").put("translation","Здравствуйте.").put("model",TranslationClient.MODEL).put("created",123L)
   .put("blocks",JSONArray().put("Привет.")).put("sentences",JSONArray().put("Здравствуйте.")).put("warnings",JSONArray().put("")).put("choices",JSONArray().put("sentences"))
  file.writeText(DocumentHistory.serialize(JSONArray().put(row)))
  val intent=Intent(app,DocumentActivity::class.java).putExtra("source","Hello.").putExtra("track","content://song")
  val screen=Robolectric.buildActivity(DocumentActivity::class.java,intent).create().start().resume();val activity=screen.get()
  try {
   assertFalse(button(activity,"Сохранить полный результат").isEnabled)
   button(activity,"История переводов").performClick();shadowOf(ShadowAlertDialog.getLatestAlertDialog()).clickOnItem(0)
   ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(android.os.Looper.getMainLooper()).idle()
   assertTrue(button(activity,"Сохранить полный результат").isEnabled)
   button(activity,"Сохранить полный результат").performClick()
   assertEquals(2,DocumentHistory.load(file.readText(),null).length())
   assertEquals("Здравствуйте.",TranslationStore(app).read("content://song","Hello."))
  } finally { screen.pause().stop().destroy() }
 }
 @Test fun corruptHistoryCannotBeOverwritten() {
  val app=RuntimeEnvironment.getApplication();val file=File(app.filesDir,"full-document-history.json");file.writeText("broken")
  val screen=Robolectric.buildActivity(DocumentActivity::class.java,Intent(app,DocumentActivity::class.java)).create().start().resume()
  try { assertFalse(button(screen.get(),"Сохранить полный результат").isEnabled);assertEquals("broken",file.readText()) }
  finally { screen.pause().stop().destroy() }
 }
}
