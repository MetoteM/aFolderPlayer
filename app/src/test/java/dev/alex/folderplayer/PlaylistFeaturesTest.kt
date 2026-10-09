package dev.alex.folderplayer

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class PlaylistFeaturesTest {
 private fun track(id:String,title:String=id,folder:String="Music/Album",filename:String="$title.mp3",album:String="",year:Int?=null,number:Int?=null,disc:Int?=null,root:String="r")=Track(id,title,"Artist",30000,folder,filename,album,"",year,number,disc,root)
 private fun save(tracks:List<Track>):Library {
  val app=RuntimeEnvironment.getApplication()
  app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks",JSONArray(tracks.map { it.json() }).toString()).commit()
  return Library(app)
 }
 @Test fun naturalSortingHandlesUnpaddedPaddedAndLargeNumbersWithoutChangingNames() {
  val names=listOf("21 Song","2 Song","11 Song","1 Song","01 Other","10 Song","3 Song","20 Song")
  assertEquals(listOf("01 Other","1 Song","2 Song","3 Song","10 Song","11 Song","20 Song","21 Song"),names.sortedWith(::naturalCompare))
  assertEquals(0,naturalCompare("Track 0002","track 2"))
  assertTrue(naturalCompare("99999999999999999999999","100000000000000000000000")<0)
  assertEquals(listOf("song 2 take 2","song 2 take 10","song 11 take 1"),listOf("song 11 take 1","song 2 take 10","song 2 take 2").sortedWith(::naturalCompare))
  val tracks=names.map { track(it) };assertEquals(names,tracks.map { it.title });assertEquals(names.sortedWith(::naturalCompare),playlistOrder(tracks,SORT_TITLE).map { it.title })
 }
 @Test fun albumYearsThenDiscsThenTagsTakePriorityOverFilename() {
  val tracks=listOf(track("new",album="A New",year=2001,number=1),track("d2",folder="Music/Old/CD2",album="Z Old",year=1986,number=1,disc=2),track("two",folder="Music/Old/CD1",filename="01.mp3",album="Z Old",year=1986,number=2,disc=1),track("one",folder="Music/Old/CD1",filename="99.mp3",album="Z Old",year=1986,number=1,disc=1),track("unknown",folder="Music/Unknown",album="Unknown"))
  assertEquals(listOf("one","two","d2","new","unknown"),playlistOrder(tracks,SORT_ALBUM).map { it.uri })
 }
 @Test fun missingTagsUseFolderAndLeadingFilenameNumber() {
  val tracks=listOf(track("ten",filename="10 Tune.mp3"),track("two",filename="2 Tune.flac"),track("one",filename="01 Tune.mp3"))
  assertEquals("Album",tracks.first().albumName());assertEquals(listOf("one","two","ten"),playlistOrder(tracks,SORT_ALBUM).map { it.uri })
  assertEquals(2,filenameTrackNumber(tracks[1]));assertEquals(3,tagNumber("03/12"));assertEquals(2,tagNumber("2/2"));assertNull(tagNumber("bad"));assertNull(tagNumber("0"));assertEquals(1986,tagYear("1986"));assertNull(tagYear("unknown"))
 }
 @Test fun titleAndFilenameModesAreIndependentOfAlbumModeAndManualOrder() {
  val tracks=listOf(track("a","Title 10",filename="02.flac"),track("b","Title 2",filename="11.mp3"),track("c","Title 1",filename="1.mp3"))
  assertEquals(listOf("a","b","c"),playlistOrder(tracks,SORT_MANUAL).map { it.uri })
  assertEquals(listOf("c","b","a"),playlistOrder(tracks,SORT_TITLE).map { it.uri })
  assertEquals(listOf("c","a","b"),playlistOrder(tracks,SORT_FILENAME).map { it.uri })
  assertEquals(listOf("c","a","b"),playbackTracks(tracks,null,2).map { it.uri })
 }
 @Test fun folderAddSupportsSubfoldersBoundariesRootsAndNoDuplicates() {
  val library=save(listOf(track("a"),track("b",folder="Music/Album/CD1"),track("wrong",folder="Music/Album2"),track("otherRoot",root="other")))
  library.savePlaylist("P",listOf("keep"));assertEquals(1,library.addFolder("P",MusicFolder("r","Music/Album"),false))
  assertEquals(1,library.addFolder("P",MusicFolder("r","Music/Album"),true));assertEquals(0,library.addFolder("P",MusicFolder("r","Music/Album"),true))
  assertEquals(listOf("keep","a","b"),library.playlist("P").toList())
  assertTrue(MusicFolder("r","Music") in musicFolders(library.tracks()));assertTrue(MusicFolder("r","Music/Album/CD1") in musicFolders(library.tracks()))
 }
 @Test fun sortIsPerPlaylistPersistsRenamesAndDeletes() {
  val library=save(emptyList());library.savePlaylist("A",emptyList());library.savePlaylist("B",emptyList());library.setPlaylistSort("A",SORT_ALBUM)
  assertEquals(SORT_MANUAL,library.playlistSort("B"));assertEquals(SORT_ALBUM,Library(RuntimeEnvironment.getApplication()).playlistSort("A"))
  assertTrue(library.renamePlaylist("A","C"));assertEquals(SORT_ALBUM,library.playlistSort("C"));assertEquals(SORT_MANUAL,library.playlistSort("A"))
  library.removePlaylist("C");library.savePlaylist("C",emptyList());assertEquals(SORT_MANUAL,library.playlistSort("C"))
 }
 @Test fun oldJsonIsReadableAndNewTagsRoundTrip() {
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear().putString("tracks","""[{"uri":"old","title":"Old","artist":"X","duration":31000,"folder":"Music"}]""").commit()
  val old=Library(app).tracks().single();assertNull(old.year);assertNull(old.trackNumber);assertEquals("",old.rootUri)
  val expected=track("new",album="Album",year=1991,number=12,disc=2);assertEquals(expected,save(listOf(expected)).tracks().single())
  val metadata=expected.item().mediaMetadata;assertEquals("Album",metadata.albumTitle);assertEquals(1991,metadata.releaseYear);assertEquals(12,metadata.trackNumber);assertEquals(2,metadata.discNumber)
 }
 @Test fun serviceStartsSortedQueueAndReordersWithoutLosingCurrentSongOrDuplicates() {
  val library=save(listOf(track("file:///one.wav","1 Song"),track("file:///two.wav","2 Song"),track("file:///ten.wav","10 Song")))
  library.savePlaylist("P",listOf("file:///ten.wav","file:///two.wav","file:///one.wav"));library.setPlaylistSort("P",SORT_TITLE)
  val app=RuntimeEnvironment.getApplication();app.getSharedPreferences("playback",Context.MODE_PRIVATE).edit().clear().commit()
  val host=Robolectric.buildService(PlaybackService::class.java).create();val service=host.get()
  try {
   assertEquals(SessionResult.RESULT_SUCCESS,service.applyPlaybackCommand(PlaybackCommands.START_QUEUE,Bundle().apply { putString("source","P");putString("uri","file:///two.wav") }))
   val player=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service) as Player
   player.pause();assertEquals("file:///one.wav",player.getMediaItemAt(0).mediaId);player.seekTo(1,1200);player.addMediaItem(player.getMediaItemAt(1))
   library.setPlaylistSort("P",SORT_MANUAL);service.applyPlaybackCommand(PlaybackCommands.REORDER_QUEUE,Bundle().apply { putString("source","P") })
   assertEquals("file:///two.wav",player.currentMediaItem?.mediaId);assertEquals(1200L,player.currentPosition);assertFalse(player.playWhenReady)
   assertEquals(4,player.mediaItemCount);assertEquals("file:///ten.wav",player.getMediaItemAt(0).mediaId)
   assertEquals(2,(0 until player.mediaItemCount).count { player.getMediaItemAt(it).mediaId=="file:///two.wav" })
   player.seekTo(2,1300);player.repeatMode=Player.REPEAT_MODE_ALL;player.play();library.setPlaylistSort("P",SORT_TITLE)
   service.applyPlaybackCommand(PlaybackCommands.REORDER_QUEUE,Bundle().apply { putString("source","P") })
   assertEquals("file:///two.wav",player.currentMediaItem?.mediaId);assertEquals(2,player.currentMediaItemIndex);assertEquals(1300L,player.currentPosition);assertTrue(player.playWhenReady);assertEquals(Player.REPEAT_MODE_ALL,player.repeatMode)
  } finally { host.destroy() }
 }
}
