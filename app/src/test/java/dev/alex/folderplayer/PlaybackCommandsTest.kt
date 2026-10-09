package dev.alex.folderplayer

import android.content.Context
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class PlaybackCommandsTest {
 private fun prefs()=RuntimeEnvironment.getApplication().getSharedPreferences("playback",Context.MODE_PRIVATE)
 private fun player(service:PlaybackService)=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(service) as Player
 @Test fun explicitModesApplyToServiceAndSurviveRestart() {
  prefs().edit().clear().commit()
  val host=Robolectric.buildService(PlaybackService::class.java).create();val service=host.get()
  try {
   for(mode in listOf(Player.REPEAT_MODE_ALL,Player.REPEAT_MODE_ONE,Player.REPEAT_MODE_OFF,Player.REPEAT_MODE_ALL)) {
    assertEquals(SessionResult.RESULT_SUCCESS,service.applyPlaybackCommand(PlaybackCommands.SET_REPEAT,Bundle().apply { putInt("mode",mode) }))
    assertEquals(mode,player(service).repeatMode)
   }
   assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE,service.applyPlaybackCommand(PlaybackCommands.SET_REPEAT,Bundle().apply { putInt("mode",99) }))
   assertEquals(Player.REPEAT_MODE_ALL,player(service).repeatMode)
  } finally { host.destroy() }
  val restored=Robolectric.buildService(PlaybackService::class.java).create()
  try { assertEquals(Player.REPEAT_MODE_ALL,player(restored.get()).repeatMode) } finally { restored.destroy() }
 }
 @Test fun queueStartsWithFullSourceAtChosenItemAndPreservesRepeat() {
  prefs().edit().clear().putInt("requestedRepeat",Player.REPEAT_MODE_ALL).commit()
  RuntimeEnvironment.getApplication().getSharedPreferences("library",Context.MODE_PRIVATE).edit().clear()
   .putString("tracks","""[{"uri":"file:///a.wav","title":"Alpha","artist":"X","duration":30000,"folder":""},{"uri":"file:///b.wav","title":"Beta","artist":"Y","duration":30000,"folder":""},{"uri":"file:///c.wav","title":"Gamma","artist":"Z","duration":30000,"folder":""}]""")
   .putString("playlists","""{"Металл":["file:///c.wav","file:///b.wav","file:///a.wav"]}""").commit()
  val host=Robolectric.buildService(PlaybackService::class.java).create();val service=host.get()
  try {
   assertEquals(SessionResult.RESULT_SUCCESS,service.applyPlaybackCommand(PlaybackCommands.START_QUEUE,Bundle().apply { putString("source","Металл");putString("uri","file:///b.wav") }))
   val p=player(service);p.pause()
   assertEquals(3,p.mediaItemCount);assertEquals(1,p.currentMediaItemIndex);assertEquals("file:///c.wav",p.getMediaItemAt(0).mediaId)
   assertEquals(Player.REPEAT_MODE_ALL,p.repeatMode);assertEquals("Металл",prefs().getString("queueSource",null))
  } finally { host.destroy() }
 }
}
