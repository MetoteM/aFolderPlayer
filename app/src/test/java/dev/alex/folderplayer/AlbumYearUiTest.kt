package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.os.Binder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.*
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AlbumYearUiTest {
 private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap { views(v.getChildAt(it)) }else emptyList()
 private fun dialog():AlertDialog { shadowOf(Looper.getMainLooper()).idle();return ShadowAlertDialog.getLatestAlertDialog() }
 private fun click(d:AlertDialog,text:String) { views(d.window!!.decorView).filterIsInstance<Button>().first { it.text.toString()==text }.performClick();shadowOf(Looper.getMainLooper()).idle() }
 private fun setup():Track { val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("album-years",Context.MODE_PRIVATE).edit().clear().commit();val t=Track("a","One","Metallica",30000,"Music/Album","01.mp3","Album","Metallica");app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks",JSONArray(listOf(t.json())).toString()).commit();return t }
 @Test fun manualAlbumYearPersistsAndCanBeResetFromUi() {
  val t=setup();val screen=Robolectric.buildActivity(Activity::class.java).setup();var changed=0;val ui=AlbumYearDialogs(screen.get(),{changed++})
  try {
   ui.show(listOf(t));shadowOf(dialog()).clickOnItem(0);click(dialog(),"Исправить год вручную")
   val edit=dialog();views(edit.window!!.decorView).filterIsInstance<EditText>().single().setText("1988");click(edit,"Сохранить")
   assertEquals(1988,Library(screen.get()).tracks().single().year);assertEquals(1,changed)
   click(dialog(),"Сбросить сохранённый год");click(dialog(),"Сбросить");assertNull(Library(screen.get()).tracks().single().year);assertEquals(2,changed)
  } finally { ui.close();screen.pause().stop().destroy() }
 }
 @Test fun searchRequiresButtonThenPreviewAndSaveBeforeChangingYear() {
  val t=setup();val screen=Robolectric.buildActivity(Activity::class.java).setup();var requests=0;var changed=0
  val client=MusicBrainzSearch { url -> requests++;object:HttpURLConnection(url) {
   override fun connect(){};override fun usingProxy()=false;override fun disconnect(){}
   override fun getResponseCode()=200
   override fun getInputStream()=ByteArrayInputStream("""{"release-groups":[{"id":"11111111-2222-3333-4444-555555555555","title":"Album","first-release-date":"1988","artist-credit":[{"name":"Metallica"}],"primary-type":"Album"}]}""".toByteArray())
  } }
  val ui=AlbumYearDialogs(screen.get(),{changed++},client)
  try {
   ui.show(listOf(t),true);shadowOf(dialog()).clickOnItem(0);assertEquals(0,requests);click(dialog(),"Найти в интернете");assertEquals(0,requests)
   Thread.sleep(1110);val search=dialog();click(search,"Поиск")
   val deadline=System.currentTimeMillis()+5000
   while(search.isShowing && System.currentTimeMillis()<deadline) { Thread.sleep(10);shadowOf(Looper.getMainLooper()).idle() }
   assertFalse(search.isShowing);assertEquals(1,requests);assertNull(Library(screen.get()).tracks().single().year)
   shadowOf(dialog()).clickOnItem(0);assertNull(Library(screen.get()).tracks().single().year);click(dialog(),"Сохранить")
   assertEquals(1988,Library(screen.get()).tracks().single().year);assertEquals(1,changed);assertEquals("MusicBrainz",AlbumYears(screen.get()).get(t.albumIdentity())!!.source)
  } finally { ui.close();screen.pause().stop().destroy() }
 }
 @Test fun playlistSortOffersMissingYearsWithoutStartingNetwork() {
  val t=setup();val app=RuntimeEnvironment.getApplication();shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder());shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
  Library(app).savePlaylist("P",listOf(t.uri));app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val a=screen.get()
  try {
   views(a.window.decorView).filterIsInstance<Button>().first { it.contentDescription=="Плейлисты" }.performClick();shadowOf(dialog()).clickOnItem(1)
   views(a.window.decorView).filterIsInstance<Button>().first { it.contentDescription=="Сортировка" }.performClick();shadowOf(dialog()).clickOnItem(3)
   assertEquals(SORT_ALBUM,Library(a).playlistSort("P"));assertTrue(views(dialog().window!!.decorView).filterIsInstance<TextView>().any { it.text.toString()=="Нет года у 1 альбомов" })
   click(dialog(),"Позже");assertNull(Library(a).tracks().single().year)
  }finally { screen.pause().stop().destroy() }
 }
 @Test fun compactSearchCleansLpAndHasOnlySearchAndClose() {
  val original=setup();val t=original.copy(album="Load (LP)");val app=RuntimeEnvironment.getApplication()
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().putString("tracks",JSONArray(listOf(t.json())).toString()).commit()
  val screen=Robolectric.buildActivity(Activity::class.java).setup();var requests=0
  val ui=AlbumYearDialogs(screen.get(),{},MusicBrainzSearch { requests++;throw java.io.IOException("Must not be called") })
  try {
   ui.show(listOf(t));shadowOf(dialog()).clickOnItem(0);click(dialog(),"Найти в интернете")
   val search=dialog();assertEquals("Load",views(search.window!!.decorView).filterIsInstance<EditText>().first().text.toString())
   val buttons=views(search.window!!.decorView).filterIsInstance<Button>().filter { it.visibility==View.VISIBLE }.map { it.text.toString() }
   assertEquals(setOf("Поиск","Закрыть"),buttons.toSet());assertEquals(2,views(search.window!!.decorView).filterIsInstance<EditText>().size)
   assertEquals(0,requests);assertNull(Library(screen.get()).tracks().single().year)

  } finally { ui.close();screen.pause().stop().destroy() }
 }

}
