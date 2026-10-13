package dev.alex.folderplayer

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SongTextTest {
 private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) } else emptyList()
 @Test fun originalIsReadableWithoutTranslatorAndRussianTranslationIsSecondary(){
  val app=RuntimeEnvironment.getApplication()
  val intent=Intent(app,SongTextActivity::class.java).putExtra("songTitle","Русская песня").putExtra("artist","Исполнитель").putExtra("source","Это оригинальный русский текст песни.")
  val host=Robolectric.buildActivity(SongTextActivity::class.java,intent).setup();val all=views(host.get().window.decorView)
  assertTrue(all.filterIsInstance<TextView>().any { it.text.toString()=="Это оригинальный русский текст песни." })
  val buttons=all.filterIsInstance<Button>();assertEquals(View.GONE,buttons.single { it.text=="Перевести" }.visibility)
  assertTrue(buttons.any { it.text=="Дополнительно" });assertFalse(buttons.any { it.text.toString().contains("полный текст") })
  host.pause().stop().destroy()
 }
 @Test fun cacheKeepsSelectedCoverAttributionAndRejectsEmptyOverwrite(){
  val store=RecognizedTextStore(RuntimeEnvironment.getApplication())
  store.save("Recognized song","Original singer","Saved words","Selected song","Other singer")
  val restored=RecognizedTextStore(RuntimeEnvironment.getApplication()).readEntry("Recognized song","Original singer")!!
  assertEquals("Other singer",restored.artist);assertEquals("Saved words",restored.text)
  try {store.save("Recognized song","Original singer","");fail()}catch(expected:IllegalArgumentException){}
  assertEquals("Saved words",store.read("Recognized song","Original singer"))
 }
 @Test fun spanishSuggestionsUseDistinctWordsAndPreserveOtherLanguages(){
  for(text in listOf("Te quiero.","Te amo.","El niño.","¿Dónde estás?","Yo no puedo vivir sin ti."))assertEquals("opus-es-ru-v1",SongTextActivity.sourceModel(text))
  assertEquals("opus-en-ru-v2",SongTextActivity.sourceModel("No, no, no. I love you."))
  assertEquals("opus-en-ru-v2",SongTextActivity.sourceModel("La la la... no."))
  assertEquals("opus-fr-ru-v1",SongTextActivity.sourceModel("Je t'aime."))
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Du hast mich gefragt."))
 }
 @Test fun missingSpanishPackageNamesRequiredLanguage(){
  val app=RuntimeEnvironment.getApplication()
  val host=Robolectric.buildActivity(SongTextActivity::class.java,Intent(app,SongTextActivity::class.java).putExtra("source","Te quiero.")).setup()
  SongTextActivity::class.java.getDeclaredMethod("missingLanguage").apply { isAccessible=true }.invoke(host.get())
  assertTrue(views(host.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Испанский → русский") })
  host.pause().stop().destroy()
 }
 @Test fun frenchIsSuggestedWithoutChangingEnglishOrGermanDetection(){
  for(text in listOf("Je t'aime.","Je suis là.","Nous attendons dans une maison.","Nous attendons le lever du soleil.","Le cœur et la lumière."))assertEquals("opus-fr-ru-v1",SongTextActivity.sourceModel(text))
  assertEquals("opus-en-ru-v2",SongTextActivity.sourceModel("I love you. You are always on my mind."))
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Ich liebe dich."))
 }
 @Test fun languagesAreSuggestedFromDistinctGermanWordsAndChineseCharacters(){
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Du hast mich gefragt. Ich liebe dich."))
  assertEquals("opus-en-ru-v2",SongTextActivity.sourceModel("Die, die, die. I will remember you."))
  assertEquals("opus-zh-ru-v1",SongTextActivity.sourceModel("我爱你。"))
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Du hast."))
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Sonne"))
  assertEquals("opus-de-ru-v1",SongTextActivity.sourceModel("Mutter"))
  assertTrue(SongTextActivity.russian("Моя мать дала мне письмо."))
 }
 @Test fun songTranslationKeepsGlobalLanguageAndPassesPerTextModel(){
  val app=RuntimeEnvironment.getApplication();val prefs=app.getSharedPreferences("extensions",0)
  prefs.edit().putString("model","opus-zh-ru-v1").putString("modelTitle","Chinese").apply()
  for(model in listOf("opus-de-ru-v1","opus-fr-ru-v1","opus-es-ru-v1")) {
   val host=Robolectric.buildActivity(SongTextActivity::class.java,Intent(app,SongTextActivity::class.java).putExtra("source","Original text")).setup()
   SongTextActivity::class.java.getDeclaredMethod("launchTranslation",Pair::class.java).apply { isAccessible=true }.invoke(host.get(),model to TranslationClient.MODELS[model]!!)
   val next=org.robolectric.Shadows.shadowOf(host.get()).nextStartedActivity
   assertEquals(model,next.getStringExtra("translationModel"));assertEquals("opus-zh-ru-v1",prefs.getString("model",null))
   host.pause().stop().destroy()
  };prefs.edit().clear().apply()
 }
 @Test fun missingGermanPackageNamesRequiredLanguage(){
  val app=RuntimeEnvironment.getApplication()
  val host=Robolectric.buildActivity(SongTextActivity::class.java,Intent(app,SongTextActivity::class.java).putExtra("source","Ich liebe dich und du liebst mich.")).setup()
  SongTextActivity::class.java.getDeclaredMethod("missingLanguage").apply { isAccessible=true }.invoke(host.get())
  assertTrue(views(host.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("Немецкий → русский") })
  host.pause().stop().destroy()
 }
 @Test fun recognitionHasSeparateSearchAndOpensReadingScreen(){
  val host=Robolectric.buildActivity(RecognitionActivity::class.java).setup();val activity=host.get()
  RecognitionActivity::class.java.getDeclaredMethod("showResult",String::class.java,String::class.java).apply { isAccessible=true }.invoke(activity,"Song","Singer")
  val buttons=views(activity.window.decorView).filterIsInstance<Button>()
  assertTrue(buttons.any { it.text=="Найти текст" });assertFalse(buttons.any { it.text.toString().contains("и перевести") })
  val source="Original words"
  RecognitionActivity::class.java.getDeclaredMethod("openText",String::class.java,String::class.java,String::class.java).apply { isAccessible=true }.invoke(activity,source,"Song","Singer")
  val next=org.robolectric.Shadows.shadowOf(activity).nextStartedActivity
  assertEquals(SongTextActivity::class.java.name,next.component!!.className);assertEquals(source,next.getStringExtra("source"));assertFalse(next.getBooleanExtra("translateNow",false))
  host.pause().stop().destroy()
 }
}
