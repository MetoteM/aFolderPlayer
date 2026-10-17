package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],manifest=Config.NONE)
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class CueSheetTest {
    private val image=Track("content://audio/album","Album image","Tagged artist",180000,"Music/Album","album.flac",rootUri="root")
    private val text="""
        REM DATE 1999
        PERFORMER "Артист"
        TITLE "Альбом"
        FILE "album.wav" WAVE
          TRACK 01 AUDIO
            TITLE "Вступление"
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            TITLE "Песня"
            PERFORMER "Гость"
            INDEX 00 00:09:00
            INDEX 01 00:10:37
    """.trimIndent()
    @Test fun imageBecomesDistinctClippedTracksWithOwnTagsAndRelativeDuration() {
        val tracks=CueTracks.expand(CueSheet.parse(text),"cue","Music/Album","root",listOf(image))
        assertEquals(listOf("Вступление","Песня"),tracks.map { it.title })
        assertEquals(listOf("Артист","Гость"),tracks.map { it.artist })
        assertEquals(10493L,tracks[0].duration);assertEquals(169507L,tracks[1].duration)
        assertTrue(tracks[0].duration<MIN_DURATION_MS);assertEquals(1999,tracks[1].year)
        assertEquals(2,tracks.map { it.uri }.toSet().size)
        val second=tracks[1].item()
        assertEquals(image.uri,second.localConfiguration!!.uri.toString())
        assertEquals(tracks[1].uri,second.mediaId)
        assertEquals(10493L,second.clippingConfiguration.startPositionMs)
        assertEquals(180000L,second.clippingConfiguration.endPositionMs)
        assertEquals(listOf(1,2),playlistOrder(tracks.reversed(),SORT_FILENAME).map { it.trackNumber })
    }
    @Test fun utf8BomUtf16AndWindows1251RetainCyrillic() {
        for(bytes in listOf(("\uFEFF"+text).toByteArray(),text.toByteArray(Charsets.UTF_16),text.toByteArray(java.nio.charset.Charset.forName("windows-1251"))))
            assertEquals("Артист",CueSheet.parse(CueSheet.decode(bytes)).artist)
    }
    @Test fun multipleFilesRestartCoordinatesAndResolveRelativeSubfolders() {
        val other=image.copy(uri="content://audio/other",sourceUri="content://audio/other",folder="Music/Album/Disc2",filename="other.flac",duration=60000)
        val sheet=CueSheet.parse("FILE \"album.flac\" WAVE\nTRACK 01 AUDIO\nINDEX 01 00:00:00\nFILE \"Disc2\\other.flac\" WAVE\nTRACK 02 AUDIO\nINDEX 01 00:00:00")
        val tracks=CueTracks.expand(sheet,"cue",image.folder,"root",listOf(image,other))
        assertEquals(listOf(180000L,60000L),tracks.map { it.duration });assertEquals(other.uri,tracks[1].sourceUri)
    }
    @Test fun brokenAndUnsafeSheetsCannotReplacePlayableOriginal() {
        for(bad in listOf(text.replace("00:10:37","00:00:00"),text.replace("00:10:37","04:00:00"),text.replace("album.wav","../album.wav"),text.replace("album.wav","missing.flac"),text.replace("00:10:37","00:10:75"),text.replace("INDEX 01 00:10:37","INDEX 00 00:10:37")))
            assertThrows(IllegalArgumentException::class.java) { CueTracks.expand(CueSheet.parse(bad),"cue",image.folder,"root",listOf(image)) }
    }
    @Test fun persistedSegmentsRestoreForQueuesPlaylistsAndOldRecordsStillLoad() {
        val context=RuntimeEnvironment.getApplication()
        val tracks=CueTracks.expand(CueSheet.parse(text),"cue",image.folder,"root",listOf(image))
        context.getSharedPreferences("library",0).edit().clear().putString("tracks",org.json.JSONArray(tracks.map { it.json() }+image.json().apply { remove("sourceUri");remove("cueStartMs");remove("cueEndMs");remove("cueSheetUri") }).toString()).commit()
        val library=Library(context);val restored=library.tracks()
        assertEquals(tracks,restored.take(2));assertEquals(image,restored[2])
        library.savePlaylist("CUE",tracks.map { it.uri }.reversed())
        assertEquals(tracks.reversed(),library.ordered(restored,"CUE",0))
        library.setTracksHidden(setOf(tracks[0].uri),true)
        assertFalse(library.playableTracks().any { it.uri==tracks[0].uri });assertTrue(library.playableTracks().any { it.uri==tracks[1].uri })
    }
}
