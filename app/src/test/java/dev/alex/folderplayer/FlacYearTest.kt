package dev.alex.folderplayer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Arrays
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class FlacYearTest {
    companion object {
        @Throws(IOException::class)
        fun comments(vararg fields: String): ByteArray {
            val b = ByteArrayOutputStream()
            number(b, 0)
            number(b, fields.size.toLong())
            for (field in fields) {
                val bytes = field.toByteArray(StandardCharsets.UTF_8)
                number(b, bytes.size.toLong())
                b.write(bytes)
            }
            return b.toByteArray()
        }

        @Throws(IOException::class)
        fun number(b: OutputStream, n: Long) {
            for (i in 0 until 4) b.write(((n shr (8 * i)).toInt()) and 255)
        }

        @Throws(IOException::class)
        fun flac(comments: ByteArray): ByteArray {
            val b = ByteArrayOutputStream()
            b.write("fLaC".toByteArray(StandardCharsets.US_ASCII))
            b.write(0x84)
            b.write(comments.size shr 16)
            b.write(comments.size shr 8)
            b.write(comments.size)
            b.write(comments)
            return b.toByteArray()
        }
    }

    @Test
    fun syntheticCommentsRead1988AndStopBeforeAudio() {
        val fixture: ByteArray
        javaClass.getResourceAsStream("/synthetic-year-comments.flac").use { input ->
            assertNotNull(input)
            fixture = input.readAllBytes()
        }
        val guarded =
            object : ByteArrayInputStream(fixture) {
                override fun read(): Int {
                    if (pos >= count) throw AssertionError("Audio read attempted")
                    return super.read()
                }

                override fun read(b: ByteArray, o: Int, n: Int): Int {
                    if (pos >= count) throw AssertionError("Audio read attempted")
                    return super.read(b, o, n)
                }
            }
        assertEquals(Integer.valueOf(1988), FlacYearReader.readYear(guarded))
        assertEquals(0, guarded.available())
    }

    @Test
    fun dateCaseIsoAndYearFallback() {
        assertEquals(
            Integer.valueOf(1988),
            FlacYearReader.readYear(ByteArrayInputStream(flac(comments("date=1988-09-07")))),
        )
        assertEquals(
            Integer.valueOf(1991),
            FlacYearReader.readYear(ByteArrayInputStream(flac(comments("DATE=unknown", "YEAR=1991")))),
        )
        assertEquals(
            Integer.valueOf(1988),
            FlacYearReader.readYear(ByteArrayInputStream(flac(comments("YEAR=2020", "DATE=1988")))),
        )
        assertNull(FlacYearReader.parseYear("19880"))
        assertNull(FlacYearReader.parseYear("0000"))
        assertNull(FlacYearReader.parseYear("unknown"))
    }

    @Test
    fun skipsLargeUnrelatedFieldsAndShortReads() {
        val data = flac(comments("WAVEFORM=" + "x".repeat(200000), "DATE=1984"))
        val input =
            object : ByteArrayInputStream(data) {
                override fun skip(n: Long): Long = 0

                override fun read(b: ByteArray, o: Int, n: Int): Int =
                    super.read(b, o, minOf(n, 3))
            }
        assertEquals(Integer.valueOf(1984), FlacYearReader.readYear(input))
    }

    @Test
    fun missingMalformedAndOtherFormatsStayUnknown() {
        assertNull(FlacYearReader.readYear(ByteArrayInputStream(flac(comments("TITLE=Song")))))
        assertNull(FlacYearReader.readYear(ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))))
        val valid = flac(comments("DATE=1983"))
        assertNull(
            FlacYearReader.readYear(ByteArrayInputStream(Arrays.copyOf(valid, valid.size - 1)))
        )
        val body = ByteArrayOutputStream()
        number(body, 0xffffffffL)
        assertNull(FlacYearReader.readYear(ByteArrayInputStream(flac(body.toByteArray()))))
        body.reset()
        number(body, 0)
        number(body, 100001)
        assertNull(FlacYearReader.readYear(ByteArrayInputStream(flac(body.toByteArray()))))
    }
}
