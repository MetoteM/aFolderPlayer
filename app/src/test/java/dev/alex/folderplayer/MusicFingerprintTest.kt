package dev.alex.folderplayer

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class MusicFingerprintTest {
    @Test
    fun silenceHasValidUnsignedCrcAndNoInventedPeaks() {
        val data = Base64.getDecoder().decode(MusicFingerprint.encode(ShortArray(16000 * 12)).split(",", limit = 2)[1])
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xCAFE2580.toInt(), b.getInt(0))
        assertEquals(56, data.size)
        assertEquals((data.size - 48).toLong(), b.getInt(8).toLong())
        val crc = CRC32()
        crc.update(data, 8, data.size - 8)
        assertEquals(crc.value, b.getInt(4).toLong() and 0xFFFFFFFFL)
        assertEquals((12 * 16000 + 3840).toLong(), b.getInt(40).toLong())
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsUnboundedRecording() {
        MusicFingerprint.encode(ShortArray(16000 * 31))
    }

    @Test
    fun interruptedFingerprintStopsBeforeProcessing() {
        Thread.currentThread().interrupt()
        try {
            MusicFingerprint.encode(ShortArray(16000 * 12))
            fail()
        } catch (expected: CancellationException) {
        } finally {
            Thread.interrupted()
        }
    }
}
