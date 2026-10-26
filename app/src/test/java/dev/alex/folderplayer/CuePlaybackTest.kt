package dev.alex.folderplayer

import android.os.Looper
import androidx.media3.common.*
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Duration

/** Read an actual WAV image through Media3, including clipping and queue transitions. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CuePlaybackTest {
    private class Drain:BaseRenderer(C.TRACK_TYPE_AUDIO) {
        private val buffer=DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
        private var ended=false
        override fun getName()="CUE PCM drain"
        override fun supportsFormat(format:Format)=C.FORMAT_HANDLED
        override fun isReady()=true
        override fun isEnded()=ended
        override fun onPositionReset(positionUs:Long,joining:Boolean){ended=false}
        override fun render(positionUs:Long,elapsedRealtimeUs:Long) {
            if(ended)return
            repeat(100) { buffer.clear();when(readSource(FormatHolder(),buffer,0)) {
                C.RESULT_BUFFER_READ->if(buffer.isEndOfStream){ended=true;return}
                C.RESULT_NOTHING_READ->return
            } }
        }
    }
    private fun await(condition:()->Boolean) {
        val until=System.nanoTime()+8_000_000_000L
        while(!condition() && System.nanoTime()<until){Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20));Thread.sleep(10)}
        assertTrue("CUE playback timed out",condition())
    }
    @Test fun twoTracksInOneImageExposeRelativeSeekDurationAndAdvance() {
        val context=RuntimeEnvironment.getApplication();val file=java.io.File.createTempFile("cue-album-",".wav",context.cacheDir)
        val pcm=ByteArray(16000)
        val wav=ByteBuffer.allocate(44+pcm.size).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray()).putInt(36+pcm.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(8000).putInt(16000).putShort(2).putShort(16).put("data".toByteArray()).putInt(pcm.size).put(pcm)
        file.writeBytes(wav.array())
        val original=Track(file.toURI().toString(),"Image","",1000,"","album.wav")
        val sheet=CueSheet.parse("FILE \"album.wav\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nTRACK 02 AUDIO\nINDEX 01 00:00:30")
        val tracks=CueTracks.expand(sheet,"cue","","",listOf(original))
        val player=ExoPlayer.Builder(context,RenderersFactory { _,_,_,_,_->arrayOf(Drain()) }).build()
        try {
            player.setMediaItems(tracks.map { it.item() });player.prepare();await { player.playbackState==Player.STATE_READY }
            assertEquals(400L,player.duration)
            player.seekTo(1,100);await { player.currentMediaItemIndex==1 && player.playbackState==Player.STATE_READY }
            assertEquals(600L,player.duration);assertEquals(100L,player.currentPosition)
            player.seekTo(0,0);val seen=mutableListOf<String>()
            player.addListener(object:Player.Listener { override fun onMediaItemTransition(item:MediaItem?,reason:Int){item?.let { seen.add(it.mediaId) }} })
            player.repeatMode=Player.REPEAT_MODE_OFF;player.play();await { player.playbackState==Player.STATE_ENDED }
            assertEquals(tracks[1].uri,player.currentMediaItem!!.mediaId);assertTrue(tracks[1].uri in seen);assertNull(player.playerError)
        } finally { player.release();file.delete() }
    }
}
