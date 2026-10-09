package dev.alex.folderplayer

import android.content.Context
import android.content.ComponentName
import android.os.Binder
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlaylistUiTest {
 private fun views(view:View):List<View> = listOf(view)+if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
 private fun button(view:View,text:String)=views(view).filterIsInstance<Button>().first { it.text.toString()==text || it.contentDescription==text }
 private fun dialog():android.app.AlertDialog { shadowOf(Looper.getMainLooper()).idle();return ShadowAlertDialog.getLatestAlertDialog() }
 @Test fun createPlaylistFromFolderThenChooseAlbumSortShowsTrackNumbers() {
  val app=RuntimeEnvironment.getApplication()
  // UI test has no live bound service. Use a valid pending Binder endpoint; service logic is tested separately.
  shadowOf(app).setComponentNameAndServiceForBindService(ComponentName(app,PlaybackService::class.java),Binder())
  shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
  val tracks=listOf(Track("a","One","Artist",30000,"Music/Album","01.mp3","Album","Artist",1986,1,1,"root"),Track("b","Two","Artist",30000,"Music/Album","2.mp3","Album","Artist",1986,2,1,"root"))
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks",JSONArray(tracks.map { it.json() }).toString()).commit()
  app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val activity=screen.get()
  try {
   button(activity.window.decorView,"Плейлисты").performClick();shadowOf(dialog()).clickOnItem(1)
   val creation=dialog();val children=views(creation.window!!.decorView)
   children.filterIsInstance<EditText>().single().setText("Альбомы")
   children.filterIsInstance<CheckBox>().first { it.text.toString()=="Добавить песни из папки" }.isChecked=true
   creation.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
   shadowOf(dialog()).clickOnItem(1) // Music/Album, not its parent Music.
   dialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
   assertEquals(listOf("a","b"),Library(app).playlist("Альбомы").toList())
   assertTrue(views(activity.window.decorView).filterIsInstance<TextView>().any { it.text.toString().startsWith("№01 · One") })
   button(activity.window.decorView,"Сортировка").performClick();shadowOf(dialog()).clickOnItem(3)
   assertEquals(SORT_ALBUM,Library(app).playlistSort("Альбомы"))
   button(activity.window.decorView,"Плейлисты").performClick();dialog().getButton(android.app.AlertDialog.BUTTON_NEUTRAL).performClick();shadowOf(dialog()).clickOnItem(0);shadowOf(dialog()).clickOnItem(1)
   dialog().getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle()
   assertEquals(2,Library(app).playlist("Альбомы").size)
  } finally { screen.pause().stop().destroy() }
 }
}
