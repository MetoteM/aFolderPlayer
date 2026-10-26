package dev.alex.folderplayer

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets

/** Reads native FLAC Vorbis comments only; never decodes or scans audio frames. */
object FlacYearReader {
    private const val MAX_METADATA = 64L * 1024 * 1024

    @JvmStatic
    @Throws(IOException::class)
    fun readYear(input: InputStream): Int? {
        return try {
            val marker = ByteArray(4)
            readFully(input, marker, 4)
            if (marker[0] != 'f'.code.toByte() || marker[1] != 'L'.code.toByte() ||
                marker[2] != 'a'.code.toByte() ||
                marker[3] != 'C'.code.toByte()
            )
                return null
            var total = 4L
            for (block in 0 until 256) {
                val header = ByteArray(4)
                readFully(input, header, 4)
                val length =
                    ((header[1].toInt() and 255) shl 16) or
                        ((header[2].toInt() and 255) shl 8) or
                        (header[3].toInt() and 255)
                total += 4L + length
                if (total > MAX_METADATA) return null
                val body = Block(input, length)
                if ((header[0].toInt() and 127) == 4) return comments(body)
                body.skip(length.toLong())
                if ((header[0].toInt() and 128) != 0) return null
            }
            null
        } catch (malformed: EOFException) {
            null
        }
    }

    @Throws(IOException::class)
    private fun comments(block: Block): Int? {
        block.skip(block.number())
        val count = block.number()
        if (count > 100000 || count > block.remaining / 4) return null
        val years = YearTags()
        for (i in 0 until count) {
            val length = block.number()
            if (length > block.remaining) throw EOFException()
            val prefix = minOf(length, 128).toInt()
            val text = ByteArray(prefix)
            block.read(text)
            block.skip(length - prefix)
            // A year/date needs only a short text field. Oversized comments (waveforms/pictures)
            // are skipped.
            if (length > 128) continue
            val field = String(text, StandardCharsets.UTF_8)
            val equal = field.indexOf('=')
            if (equal < 0) continue
            years.add(field.substring(0, equal), field.substring(equal + 1))
        }
        return years.resolve(null)
    }

    @JvmStatic
    fun parseYear(text: String): Int? = YearTags.parse(text)

    @Throws(IOException::class)
    private fun readFully(input: InputStream, bytes: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = input.read(bytes, offset, count - offset)
            if (n < 0) throw EOFException()
            if (n == 0) {
                val c = input.read()
                if (c < 0) throw EOFException()
                bytes[offset++] = c.toByte()
            } else offset += n
        }
    }

    private class Block(val input: InputStream, size: Int) {
        var remaining: Long = size.toLong()
        val scratch = ByteArray(8192)

        @Throws(IOException::class)
        fun read(bytes: ByteArray) {
            if (bytes.size > remaining) throw EOFException()
            readFully(input, bytes, bytes.size)
            remaining -= bytes.size
        }

        @Throws(IOException::class)
        fun number(): Long {
            val b = ByteArray(4)
            read(b)
            return (b[0].toLong() and 255L) or
                ((b[1].toLong() and 255L) shl 8) or
                ((b[2].toLong() and 255L) shl 16) or
                ((b[3].toLong() and 255L) shl 24)
        }

        @Throws(IOException::class)
        fun skip(size: Long) {
            if (size < 0 || size > remaining) throw EOFException()
            var left = size
            while (left > 0) {
                val skipped = input.skip(left)
                if (skipped > 0) left -= skipped
                else {
                    val n = minOf(left, scratch.size.toLong()).toInt()
                    readFully(input, scratch, n)
                    left -= n
                }
            }
            remaining -= size
        }
    }
}
