package dev.alex.folderplayer

import java.io.File
import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class Mp3TagsTest {
    private fun resource(name:String)=javaClass.getResourceAsStream("/export/$name")!!.use { it.readBytes() }
    private fun frames(bytes:ByteArray):Map<String,ByteArray> {
        var size=0;for(i in 6..9)size=(size shl 7)+(bytes[i].toInt() and 127)
        var p=10;val result=linkedMapOf<String,ByteArray>()
        while(p+10<=size+10) {
            val id=String(bytes,p,4,Charsets.US_ASCII);val n=ByteBuffer.wrap(bytes,p+4,4).int
            result[id]=bytes.copyOfRange(p+10,p+10+n);p+=10+n
        }
        return result
    }
    private fun audio(bytes:ByteArray):ByteArray { var size=0;for(i in 6..9)size=(size shl 7)+(bytes[i].toInt() and 127);return bytes.copyOfRange(size+10,bytes.size) }
    private fun unicode(body:ByteArray)=String(body,1,body.size-1,Charsets.UTF_16)
    @Test fun realMp3GetsUnicodeTagsAndEmbeddedJpegWithoutChangingAnyAudioByte() {
        val raw=File.createTempFile("mp3-raw-",".mp3");val out=File.createTempFile("mp3-tagged-",".mp3")
        try {
            val original=resource("tone.mp3");val cover=resource("cover.jpg");raw.writeBytes(original)
            assertTrue(Mp3Tags.write(raw,out,"Песня — проверка","Исполнитель",cover))
            val bytes=out.readBytes();val tags=frames(bytes)
            assertEquals("Песня — проверка",unicode(tags.getValue("TIT2")));assertEquals("Исполнитель",unicode(tags.getValue("TPE1")))
            val picture=tags.getValue("APIC");val prefix=byteArrayOf(0)+"image/jpeg".toByteArray()+byteArrayOf(0,3,0)
            assertArrayEquals(prefix+cover,picture);assertArrayEquals(original,audio(bytes))
            val cwd=File(System.getProperty("user.dir"));val dir=File(cwd,if(cwd.name=="app")"build/reports/export-fixtures" else "app/build/reports/export-fixtures").apply { mkdirs() }
            File(dir,"tagged.mp3").writeBytes(bytes)
        } finally { raw.delete();out.delete() }
    }
    @Test fun retaggingPreservesExistingCoverAndAudioWithoutDuplicatingTags() {
        val raw=File.createTempFile("mp3-raw-",".mp3");val first=File.createTempFile("mp3-first-",".mp3");val second=File.createTempFile("mp3-second-",".mp3")
        try {
            raw.writeBytes(resource("tone.mp3"));Mp3Tags.write(raw,first,"Old","Artist",resource("cover.jpg"))
            assertTrue(Mp3Tags.write(first,second,"Новое","Автор",null))
            assertEquals("Новое",unicode(frames(second.readBytes()).getValue("TIT2")))
            assertArrayEquals(frames(first.readBytes()).getValue("APIC"),frames(second.readBytes()).getValue("APIC"))
            assertEquals(setOf("TIT2","TPE1","APIC"),frames(second.readBytes()).keys)
            assertArrayEquals(resource("tone.mp3"),audio(second.readBytes()))
        } finally { raw.delete();first.delete();second.delete() }
    }
    @Test fun cancellationDeletesIncompleteTaggedFile() {
        val raw=File.createTempFile("mp3-raw-",".mp3");val out=File.createTempFile("mp3-tagged-",".mp3")
        try { raw.writeBytes(resource("tone.mp3"));assertThrows(CancellationException::class.java) { Mp3Tags.write(raw,out,"Song","Artist",null) { false } };assertFalse(out.exists()) }
        finally { raw.delete();out.delete() }
    }
    @Test fun existingV24Utf8AlbumIsConvertedAndPreserved() {
        val raw=File.createTempFile("mp3-v4-",".mp3");val out=File.createTempFile("mp3-v3-",".mp3")
        fun sync(n:Int)=byteArrayOf(((n shr 21) and 127).toByte(),((n shr 14) and 127).toByte(),((n shr 7) and 127).toByte(),(n and 127).toByte())
        try {
            val album="Альбом "+"я".repeat(80);val text=byteArrayOf(3)+album.toByteArray(Charsets.UTF_8)
            val frame="TALB".toByteArray()+sync(text.size)+byteArrayOf(0,0)+text
            raw.writeBytes("ID3".toByteArray()+byteArrayOf(4,0,0)+sync(frame.size)+frame+resource("tone.mp3"))
            Mp3Tags.write(raw,out,"Название","Автор",null)
            assertEquals(album,unicode(frames(out.readBytes()).getValue("TALB")));assertArrayEquals(resource("tone.mp3"),audio(out.readBytes()))
        } finally { raw.delete();out.delete() }
    }

}
