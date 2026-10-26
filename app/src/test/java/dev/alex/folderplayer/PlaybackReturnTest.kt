package dev.alex.folderplayer

import android.content.Context
import android.widget.EditText
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PlaybackReturnTest {
 private fun prepare() {
  val app=RuntimeEnvironment.getApplication()
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("playlists","{\"Металл\":[\"a\",\"b\"]}").commit()
  app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().putString("queue","[\"a\",\"b\"]").putString("queueSource","Металл").commit()
 }
 private fun field(activity:MainActivity,name:String):Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }.get(activity)
 @Test fun returningFromAnotherTaskOpensPlayingPlaylistAndClearsSearch() {
  prepare();val screen=Robolectric.buildActivity(MainActivity::class.java).create().start().resume();val activity=screen.get()
  try {
   assertEquals("Металл",field(activity,"selectedPlaylist"))
   MainActivity::class.java.getDeclaredField("selectedPlaylist").apply { isAccessible=true }.set(activity,null)
   (field(activity,"search") as EditText).setText("другая песня")
   screen.pause().stop().start().resume()
   assertEquals("Металл",field(activity,"selectedPlaylist"));assertEquals("",(field(activity,"search") as EditText).text.toString())
  } finally { screen.pause().stop().destroy() }
 }
 @Test fun recreationAlsoOpensPlayingPlaylist() {
  prepare();val first=Robolectric.buildActivity(MainActivity::class.java).create().start()
  first.stop().destroy()
  val second=Robolectric.buildActivity(MainActivity::class.java).create().start()
  try { assertEquals("Металл",field(second.get(),"selectedPlaylist")) } finally { second.stop().destroy() }
 }
 @Test fun deletedSourceFallsBackToLibrary() {
  prepare();RuntimeEnvironment.getApplication().getSharedPreferences("playback",Context.MODE_PRIVATE).edit().putString("queueSource","Удалённый").commit()
  val screen=Robolectric.buildActivity(MainActivity::class.java).create().start()
  try { assertNull(field(screen.get(),"selectedPlaylist")) } finally { screen.stop().destroy() }
 }
}
