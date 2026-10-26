package dev.alex.folderplayer

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class AlbumYearsTest {
 private fun track(id:String="a",folder:String="Music/Album",year:Int?=null,root:String="r")=Track(id,id,"Artist",30000,folder,"01.mp3","Album","Artist",year,1,1,root)
 private fun store():AlbumYears { val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("album-years",Context.MODE_PRIVATE).edit().clear().commit();return AlbumYears(app) }
 private fun raw(items:List<Track>) { RuntimeEnvironment.getApplication().getSharedPreferences("library",Context.MODE_PRIVATE).edit().putString("tracks",JSONArray(items.map { it.json() }).toString()).commit() }
 @Test fun savedMissingYearAppliesToWholeAlbumAndSurvivesNewLibraryAndRescanData() {
  val saved=store();val a=track();val b=track("b");saved.save(a.albumIdentity(),AlbumYear(1988,"MusicBrainz","id","Album","Artist","1988-09-07"));raw(listOf(a,b))
  val app=RuntimeEnvironment.getApplication();assertEquals(listOf(1988,1988),Library(app).tracks().map { it.year })
  raw(listOf(b,a));assertEquals(listOf(1988,1988),Library(app).tracks().map { it.year });assertEquals("1988-09-07",AlbumYears(app).get(a.albumIdentity())!!.date)
  assertNull(JSONArray(app.getSharedPreferences("library",Context.MODE_PRIVATE).getString("tracks","[]")).getJSONObject(0).opt("year"))
 }
 @Test fun taggedAndExplicitFolderYearsBeatNetworkSupplementButManualCanOverrideAndReset() {
  val saved=store();val a=track(year=1983);saved.save(a.albumIdentity(),AlbumYear(2020,"MusicBrainz"));assertEquals(1983,saved.apply(listOf(a)).single().year)
  saved.save(a.albumIdentity(),AlbumYear(1984,"Вручную",overridesTags=true));assertEquals(1984,saved.apply(listOf(a)).single().year)
  saved.remove(a.albumIdentity());assertEquals(1983,saved.apply(listOf(a)).single().year)
  val folder=track(folder="Music/1986 - Album");saved.save(folder.albumIdentity(),AlbumYear(2020,"MusicBrainz"));assertEquals(1986,saved.apply(listOf(folder)).single().year)
 }
 @Test fun folderDatesRequireExplicitSyntaxAndContradictoryDatesStayUnknown() {
  store();assertEquals(1983,folderAlbumYear(track(folder="Music/1983 - Album")));assertEquals(1984,folderAlbumYear(track(folder="Music/Album (1984)")))
  assertEquals(1986,folderAlbumYear(track(folder="Music/1986 - Album/CD1")))
  assertNull(folderAlbumYear(track(folder="Music/Album 2000")));assertNull(folderAlbumYear(track(folder="Music/1984")));assertNull(folderAlbumYear(track(folder="Music/1983 - Album (2020)")))
 }
 @Test fun identitiesSeparateRootsAndFoldersButCombineDiscs() {
  val saved=store();val a=track(folder="Music/Album/CD1");val b=track("b",folder="Music/Album/CD2")
  assertEquals(a.albumIdentity(),b.albumIdentity());saved.save(a.albumIdentity(),AlbumYear(1988,"MusicBrainz"))
  val other=track("c",root="other");val separate=track("d",folder="Other/Album")
  assertEquals(listOf(1988,1988,null,null),saved.apply(listOf(a,b,other,separate)).map { it.year });assertEquals(3,albumGroups(listOf(a,b,other,separate)).size)
 }
 @Test fun savedYearsChangeAlbumOrderWithoutTouchingPlaylistOrderOrFileMetadata() {
  val saved=store();val old=track(folder="Old/Album");val newer=track("b",folder="New/Album",year=1991)
  saved.save(old.albumIdentity(),AlbumYear(1983,"MusicBrainz"));assertEquals(listOf("a","b"),playlistOrder(saved.apply(listOf(newer,old)),SORT_ALBUM).map { it.uri });assertNull(old.year)
 }
 @Test fun persistedYearReordersPlayingPlaylistKeepingSongPositionAndRepeat() {
  val saved=store();val older=track("file:///old.wav",folder="Old/Album");val newer=track("file:///new.wav",folder="New/Album",year=1991);raw(listOf(newer,older))
  val app=RuntimeEnvironment.getApplication();val library=Library(app);library.savePlaylist("P",listOf(newer.uri,older.uri));library.setPlaylistSort("P",SORT_ALBUM)
  app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val host=org.robolectric.Robolectric.buildService(PlaybackService::class.java).create();val service=host.get()
  try {
   service.applyPlaybackCommand(PlaybackCommands.START_QUEUE,android.os.Bundle().apply { putString("source","P");putString("uri",newer.uri) })
   val player=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service) as androidx.media3.common.Player
   player.pause();player.seekTo(0,1500);player.repeatMode=androidx.media3.common.Player.REPEAT_MODE_ALL
   saved.save(older.albumIdentity(),AlbumYear(1983,"MusicBrainz"))
   service.applyPlaybackCommand(PlaybackCommands.REORDER_QUEUE,android.os.Bundle().apply { putString("source","P") })
   assertEquals(older.uri,player.getMediaItemAt(0).mediaId);assertEquals(newer.uri,player.currentMediaItem?.mediaId);assertEquals(1500L,player.currentPosition);assertFalse(player.playWhenReady);assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL,player.repeatMode)
  } finally { host.destroy() }
 }

}
