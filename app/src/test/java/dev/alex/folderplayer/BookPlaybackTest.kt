package dev.alex.folderplayer

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
class BookPlaybackTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private val books get()=Audiobooks(app)
    private val prefs get()=app.getSharedPreferences("playback",0)
    private fun book(id:String)=Audiobook(id,id,id,listOf(BookChapter("file:///$id-1.wav","1",120000),BookChapter("file:///$id-2.wav","2",180000)))
    private fun player(s:PlaybackService)=PlaybackService::class.java.getDeclaredField("player").apply { isAccessible=true }.get(s) as Player
    private fun start(s:PlaybackService,id:String,uri:String?=null,pos:Long=0)=s.applyPlaybackCommand(PlaybackCommands.START_BOOK,Bundle().apply { putString("bookId",id);if(uri!=null){putString("uri",uri);putLong("position",pos)} })
    @Before fun clear(){for(name in listOf("playback","playback-position","library","audiobooks"))app.getSharedPreferences(name,0).edit().clear().commit()}
    @Test fun bookResumesAtSavedChapterAndSpeedAndRestoresAfterServiceRestart(){
        val b=book("a");books.put(b);books.savePosition(b.id,b.chapters[1].uri,43000);books.setSpeed(b.id,1.5f)
        prefs.edit().putInt("requestedRepeat",Player.REPEAT_MODE_ALL).commit()
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        try {
            assertEquals(SessionResult.RESULT_SUCCESS,start(host.get(),b.id))
            val p=player(host.get());p.pause()
            assertEquals(2,p.mediaItemCount);assertEquals(1,p.currentMediaItemIndex);assertEquals(43000L,p.currentPosition)
            assertEquals(1.5f,p.playbackParameters.speed);assertEquals(Player.REPEAT_MODE_OFF,p.repeatMode);assertFalse(p.shuffleModeEnabled)
            p.seekTo(57000)
        } finally { host.destroy() }
        val restored=Robolectric.buildService(PlaybackService::class.java).create()
        try {val p=player(restored.get());assertEquals(1,p.currentMediaItemIndex);assertEquals(57000L,p.currentPosition);assertEquals(1.5f,p.playbackParameters.speed)}finally { restored.destroy() }
    }
    @Test fun switchingBooksAndThenMusicKeepsIndependentPositionsAndResetsSpeed(){
        val a=book("a");val b=book("b");books.put(a);books.put(b);books.setSpeed(a.id,1.75f)
        app.getSharedPreferences("library",0).edit().putString("tracks","""[{"uri":"file:///music.wav","title":"Music","artist":"","duration":30000,"folder":""}]""").commit()
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val s=host.get();start(s,a.id);val p=player(s);p.pause();p.seekTo(24000)
            start(s,b.id);p.pause();p.seekTo(36000)
            start(s,a.id);p.pause();assertEquals(24000L,p.currentPosition)
            assertEquals(36000L,books.position(b).position)
            assertEquals(SessionResult.RESULT_SUCCESS,s.applyPlaybackCommand(PlaybackCommands.START_QUEUE,Bundle().apply {putString("uri","file:///music.wav")}))
            p.pause();assertEquals(1f,p.playbackParameters.speed);assertNull(prefs.getString("activeBook",null));assertEquals(24000L,books.position(a).position)
        } finally { host.destroy() }
    }
    @Test fun bookmarkJumpAndCompletedBookRestartAreExplicit(){
        val b=book("a");books.put(b);books.savePosition(b.id,b.chapters.last().uri,180000,true)
        val host=Robolectric.buildService(PlaybackService::class.java).create()
        try {
            val s=host.get();val p=player(s)
            assertEquals(SessionResult.RESULT_SUCCESS,start(s,b.id));p.pause();assertEquals(0,p.currentMediaItemIndex);assertEquals(0L,p.currentPosition)
            assertEquals(SessionResult.RESULT_SUCCESS,start(s,b.id,b.chapters[1].uri,62000));p.pause();assertEquals(1,p.currentMediaItemIndex);assertEquals(62000L,p.currentPosition)
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE,start(s,b.id,"file:///not-in-book.wav"))
            assertEquals(SessionResult.RESULT_ERROR_BAD_VALUE,s.applyPlaybackCommand(PlaybackCommands.BOOK_SPEED,Bundle().apply {putFloat("speed",Float.NaN)}))
        }finally { host.destroy() }
    }
}
