package dev.alex.folderplayer

import android.os.Looper
import androidx.media3.common.C
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.common.Player
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.source.SilenceMediaSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.time.Duration

/** Real ExoPlayer scheduling/automatic transitions; synthetic PCM and a headless renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class QueuePlaybackTest {
 private class DrainRenderer : BaseRenderer(C.TRACK_TYPE_AUDIO) {
  private val buffer=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
  private var ended=false
  override fun getName()="Headless PCM drain"
  override fun supportsFormat(format:androidx.media3.common.Format)=C.FORMAT_HANDLED
  override fun isReady()=true
  override fun isEnded()=ended
  override fun onPositionReset(positionUs:Long,joining:Boolean) { ended=false }
  override fun render(positionUs:Long,elapsedRealtimeUs:Long) {
   if(ended) return
   repeat(100) {
    buffer.clear()
    when(readSource(FormatHolder(),buffer,0)) {
     C.RESULT_BUFFER_READ -> if(buffer.isEndOfStream) { ended=true; return }
     C.RESULT_NOTHING_READ -> return
    }
   }
  }
 }
 private fun player():ExoPlayer {
  val factory=RenderersFactory { _,_,_,_,_ -> arrayOf(DrainRenderer()) }
  return ExoPlayer.Builder(RuntimeEnvironment.getApplication(),factory).build().apply {
   setPauseAtEndOfMediaItems(false)
   setMediaSources((0..2).map { SilenceMediaSource.Factory().setDurationUs(100_000).createMediaSource() })
  }
 }
 private fun await(condition:()->Boolean) {
  val deadline=System.nanoTime()+5_000_000_000L
  while(!condition() && System.nanoTime()<deadline) {
   Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); Thread.sleep(10)
  }
  assertTrue("Playback condition timed out",condition())
 }
 @Test fun offAutomaticallyAdvancesAndStopsAtQueueEnd() {
  val p=player();val seen=mutableListOf(p.currentMediaItemIndex)
  p.addListener(object:Player.Listener { override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { seen.add(p.currentMediaItemIndex) } })
  try { p.repeatMode=Player.REPEAT_MODE_OFF;p.prepare();p.play();await { p.playbackState==Player.STATE_ENDED }
   assertEquals(listOf(0,1,2),seen);assertEquals(2,p.currentMediaItemIndex)
  } finally { p.release() }
 }
 @Test fun allLoopsWholeQueue() {
  val p=player();val seen=mutableListOf(p.currentMediaItemIndex)
  p.addListener(object:Player.Listener { override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { seen.add(p.currentMediaItemIndex) } })
  try { p.repeatMode=Player.REPEAT_MODE_ALL;p.prepare();p.play();await { seen.size>=4 }
   assertEquals(listOf(0,1,2,0),seen.take(4));assertNotEquals(Player.STATE_ENDED,p.playbackState)
  } finally { p.release() }
 }
 @Test fun oneRepeatsCurrentThenSwitchingOffResumesQueue() {
  val p=player();var repeats=0
  p.addListener(object:Player.Listener { override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) repeats++ } })
  try { p.seekTo(1,0);p.repeatMode=Player.REPEAT_MODE_ONE;p.prepare();p.play();await { repeats>=2 }
   assertEquals(1,p.currentMediaItemIndex);p.repeatMode=Player.REPEAT_MODE_OFF;await { p.playbackState==Player.STATE_ENDED };assertEquals(2,p.currentMediaItemIndex)
  } finally { p.release() }
 }
 @Test fun shuffleOffVisitsEveryItemOnce() {
  val p=player();p.repeatMode=Player.REPEAT_MODE_OFF;p.shuffleModeEnabled=true
  p.seekToDefaultPosition(p.currentTimeline.getFirstWindowIndex(true))
  val seen=mutableListOf(p.currentMediaItemIndex)
  p.addListener(object:Player.Listener { override fun onMediaItemTransition(item:androidx.media3.common.MediaItem?,reason:Int) { seen.add(p.currentMediaItemIndex) } })
  try { p.prepare();p.play();await { p.playbackState==Player.STATE_ENDED }
   assertEquals(3,seen.size);assertEquals(setOf(0,1,2),seen.toSet())
  } finally { p.release() }
 }
 @Test fun moveQueueKeepsCurrentItemPositionAndPause() {
  val p=player()
  try {
   p.seekTo(1,40);val item=p.currentMediaItem;val position=p.currentPosition
   p.moveMediaItem(1,2)
   assertEquals(item,p.currentMediaItem);assertEquals(2,p.currentMediaItemIndex);assertEquals(position,p.currentPosition);assertFalse(p.playWhenReady)
  } finally { p.release() }
 }
 @Test fun sourceQueueIncludesTracksHiddenBySearchAndKeepsPlaylistOrder() {
  val tracks=listOf(Track("a","Alpha","X",30000,""),Track("b","Beta","Y",30000,""),Track("c","Gamma","Z",30000,""))
  assertEquals(listOf("c","a","b"),playbackTracks(tracks,listOf("c","a","missing","b"),0).map { it.uri })
  val queue=playbackTracks(tracks,null,0)
  assertEquals(1,queue.filter { it.title=="Beta" }.size);assertEquals(listOf("a","b","c"),queue.map { it.uri });assertEquals(1,queue.indexOfFirst { it.uri=="b" })
 }
}
