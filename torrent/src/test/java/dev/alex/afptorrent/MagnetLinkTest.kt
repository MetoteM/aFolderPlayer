package dev.alex.afptorrent

import org.junit.Assert.*
import org.junit.Test

class MagnetLinkTest {
    private val hash="0123456789abcdef0123456789abcdef01234567"
    @Test fun copiedMessengerTextHtmlAndInvisibleCharactersKeepTrackersEscaped() {
        val expected="magnet:?xt=urn:btih:$hash&tr=https%3A%2F%2Fexample.test%2Fa%3Fx%3D1%26y%3D2"
        assertEquals(expected,MagnetLink.normalize("Вот альбом: <$expected>"))
        assertEquals(expected,MagnetLink.normalize("\uFEFFmagnet:?xt=urn:btih:$hash\u200B&amp;tr=https%3A%2F%2Fexample.test%2Fa%3Fx%3D1%26y%3D2"))
        assertEquals(expected,MagnetLink.normalize(expected.replace("&tr=","\n&tr=")))
        assertEquals(expected,MagnetLink.normalize(java.net.URLEncoder.encode(expected,"UTF-8")))
    }
    @Test fun supportsV1Base32AndV2AndRejectsTruncatedHashes() {
        assertTrue(MagnetLink.normalize("MAGNET://?XT=urn:btih:"+"A".repeat(32)).startsWith("magnet:?xt="))
        assertTrue(MagnetLink.normalize("magnet:?xt=urn:btmh:1220"+"a".repeat(64)).contains("btmh"))
        for(text in listOf("https://example.test/album","magnet:?xt=urn:btih:123","magnet:?dn=album"))
            assertThrows(IllegalArgumentException::class.java) { MagnetLink.normalize(text) }
    }
    @Test fun cueCanBeSelectedAlongsideAudioButIsNotAudioItself() {
        val files=listOf(TorrentFile(0,"album.flac",900,true),TorrentFile(1,"album.cue",100,false),TorrentFile(2,"run.exe",1,false))
        assertEquals(1000,TorrentRules.selectedSize(files,setOf(0,1),1000))
        assertThrows(IllegalArgumentException::class.java) { TorrentRules.selectedSize(files,setOf(1),1000) }
        assertThrows(IllegalArgumentException::class.java) { TorrentRules.selectedSize(files,setOf(0,2),1000) }
    }
}
