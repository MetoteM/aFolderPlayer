package dev.alex.folderplayer
import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class RandomOrderTest {
 private fun prepare():Library {
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val tracks=(1..12).map { Track("file:///$it.mp3","$it","Автор",30000,"Альбом") }
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks",org.json.JSONArray(tracks.map { it.json() }).toString()).commit()
  return Library(app).apply { savePlaylist("Тест",tracks.map { it.uri }) }
 }
 @Test fun randomSortIsStableAndDoesNotOverwriteSavedManualOrder() {
  val library=prepare();val original=library.playlist("Тест").toList();library.randomize(library.tracks(),"Тест",0)
  val shuffled=library.ordered(library.tracks(),"Тест",0).map { it.uri }
  assertEquals(original.toSet(),shuffled.toSet());assertEquals(shuffled,Library(RuntimeEnvironment.getApplication()).ordered(library.tracks(),"Тест",0).map { it.uri });assertEquals(original,library.playlist("Тест").toList())
  library.clearRandom("Тест");assertEquals(original,library.ordered(library.tracks(),"Тест",0).map { it.uri })
 }
 @Test fun serviceAndVisibleListUseIdenticalRandomOrderForLibraryAndPlaylist() {
  val library=prepare();val app=RuntimeEnvironment.getApplication();val host=Robolectric.buildService(PlaybackService::class.java).create();val service=host.get()
  val player=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service) as Player
  try { for(source in listOf("","Тест")) {
   library.randomize(library.tracks(),source,0);val order=library.ordered(library.tracks(),source,0)
   service.applyPlaybackCommand(PlaybackCommands.START_QUEUE,Bundle().apply { putString("source",source);putString("uri",order[2].uri) });player.pause();player.seekTo(1000)
   assertEquals(order.map { it.uri },(0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId })
   val id=player.currentMediaItem!!.mediaId;library.randomize(library.tracks(),source,0);service.applyPlaybackCommand(PlaybackCommands.REORDER_QUEUE,Bundle().apply { putString("source",source) })
   assertEquals(id,player.currentMediaItem!!.mediaId);assertEquals(1000L,player.currentPosition);assertFalse(player.playWhenReady)
  } } finally { host.destroy() }
 }
}
