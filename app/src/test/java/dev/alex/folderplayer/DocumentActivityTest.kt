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
 @Test fun completedResultSavesToSongWithoutReadingOrWritingArchive() {
  val app=RuntimeEnvironment.getApplication();val file=File(app.filesDir,"full-document-history.json");file.writeText("unreadable old archive")
  val row=JSONObject().put("source","Hello.").put("model",TranslationClient.MODEL)
   .put("blocks",JSONArray().put("Привет.")).put("sentences",JSONArray().put("Здравствуйте.")).put("warnings",JSONArray().put("")).put("choices",JSONArray().put("sentences"))
  val state=android.os.Bundle().apply { putString("completed",row.toString()) }
  val intent=Intent(app,DocumentActivity::class.java).putExtra("source","Hello.").putExtra("track","content://song")
  val screen=Robolectric.buildActivity(DocumentActivity::class.java,intent).create(state).start().resume()
  try {
   assertTrue(button(screen.get(),"Сохранить для песни").isEnabled)
   button(screen.get(),"Сохранить для песни").performClick()
   assertEquals("Здравствуйте.",TranslationStore(app).read("content://song","Hello."))
   assertEquals("unreadable old archive",file.readText())
   assertFalse(views(screen.get().window.decorView).filterIsInstance<Button>().any { it.text.contains("История") })
  } finally { screen.pause().stop().destroy() }
 }
 @Test fun perTextTranslationModelOverridesOnlyCurrentDocument() {
  val app=RuntimeEnvironment.getApplication()
  val prefs=app.getSharedPreferences("extensions",0)
  prefs.edit().putString("model","opus-zh-ru-v1").putString("modelTitle","Chinese").commit()
  val screen=Robolectric.buildActivity(DocumentActivity::class.java,Intent(app,DocumentActivity::class.java)
   .putExtra("source","Ich liebe dich.").putExtra("translationModel","opus-de-ru-v1")).create().start().resume()
  try {
   val model=DocumentActivity::class.java.getDeclaredField("model").apply { isAccessible=true }.get(screen.get())
   assertEquals("opus-de-ru-v1",model)
   assertEquals("opus-zh-ru-v1",prefs.getString("model",null))
  } finally { screen.pause().stop().destroy();prefs.edit().clear().commit() }
 }
}
