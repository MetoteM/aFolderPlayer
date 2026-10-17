package dev.alex.folderplayer

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CancellationException

class YandexMp3TransferTest {
    private val audio=byteArrayOf(0xff.toByte(),0xfb.toByte(),0x90.toByte(),0)+ByteArray(1024) { 7 }
    @Test fun fullMp3IsPreservedByteForByteIncludingId3AndReportsProgress() {
        val bytes="ID3".toByteArray()+byteArrayOf(4,0,0,0,0,0,3)+byteArrayOf(1,2,3)+audio
        val file=File.createTempFile("mp3-export-",".part")
        try {
            var read=0L
            YandexMp3Transfer.copy(bytes.inputStream(),file,bytes.size.toLong(),{ true },progress={ n,_->read=n })
            assertArrayEquals(bytes,file.readBytes());assertEquals(bytes.size.toLong(),read)
        } finally { file.delete() }
    }
    @Test fun htmlTruncationAndOversizedHeadersAreRejectedAndPartialDataRemoved() {
        for((bytes,length) in listOf("<html>Access denied</html>".toByteArray() to -1L,audio to (audio.size+10L),audio to (YandexMp3Transfer.MAX_BYTES+1))) {
            val file=File.createTempFile("mp3-export-",".part")
            try {
                assertThrows(IllegalArgumentException::class.java) { YandexMp3Transfer.copy(bytes.inputStream(),file,length,{ true }) }
                // Oversized responses are rejected before a stream is written.
                if(length<=YandexMp3Transfer.MAX_BYTES)assertFalse(file.exists()) else assertEquals(0,file.length())
            } finally { file.delete() }
        }
    }
    @Test fun cancellationAndNetworkPolicyChangeDeletePartialDownloads() {
        val file=File.createTempFile("mp3-export-",".part")
        try {
            var active=true
            assertThrows(CancellationException::class.java) {
                YandexMp3Transfer.copy(audio.inputStream(),file,-1,{ active },progress={ _,_->active=false })
            }
            assertFalse(file.exists())
            assertThrows(IllegalStateException::class.java) {
                YandexMp3Transfer.copy(audio.inputStream(),file,-1,{ true },network={ error("Wi-Fi only") })
            }
            assertFalse(file.exists())
        } finally { file.delete() }
    }
}
