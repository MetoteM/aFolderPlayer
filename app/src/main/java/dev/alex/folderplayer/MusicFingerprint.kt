package dev.alex.folderplayer

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.sin

/**
 * Java port of the ShazamIO 0.4.0.0 fingerprint algorithm (MIT, dotX12). Fixed missing fourth band
 * and spreading history; self-contained radix-2 FFT. Input is signed PCM16, mono, 16000 Hz. No
 * audio or identifying metadata is encoded.
 */
object MusicFingerprint {
    private const val N = 2048
    private const val MASK = 2047

    private class Peak(val frame: Int, val magnitude: Int, val bin: Int)

    @JvmStatic
    fun encode(samples: ShortArray): String {
        if (samples.size < 16000 * 3 || samples.size > 16000 * 30)
            throw IllegalArgumentException("Audio duration")
        val spectra = Array(256) { DoubleArray(1025) }
        val spread = Array(256) { DoubleArray(1025) }
        val ring = ShortArray(N)
        val window = DoubleArray(N)
        for (i in 0 until N) window[i] = .5 - .5 * cos(2 * PI * (i + 1) / (N + 1))
        val bands: List<MutableList<Peak>> = List(4) { ArrayList() }
        var pos = 0
        var index = 0
        var frames = 0
        var off = 0
        while (off + 128 <= samples.size) {
            if (Thread.currentThread().isInterrupted) throw CancellationException()
            System.arraycopy(samples, off, ring, pos, 128)
            pos = (pos + 128) and MASK
            val re = DoubleArray(N)
            val im = DoubleArray(N)
            for (i in 0 until N) re[i] = ring[(pos + i) and MASK] * window[i]
            fft(re, im)
            val power = spectra[index]
            for (i in 0..1024)
                power[i] = max(1e-10, (re[i] * re[i] + im[i] * im[i]) / 131072)
            val current = power.clone()
            for (i in 0 until 1022)
                current[i] = max(power[i], max(power[i + 1], power[i + 2]))
            for (lag in intArrayOf(1, 3, 6)) {
                val old = spread[(index - lag) and 255]
                for (i in 0..1024) old[i] = max(old[i], current[i])
            }
            spread[index] = current
            index = (index + 1) and 255
            frames++
            if (frames < 46) {
                off += 128
                continue
            }
            val center = spectra[(index - 46) and 255]
            val nearby = spread[(index - 49) and 255]
            for (bin in 10 until 1015) {
                val p = center[bin]
                if (p < 1.0 / 64 || p < nearby[bin - 1]) continue
                var m = 0.0
                for (d in intArrayOf(-10, -7, -4, -3, 1, 2, 5, 8)) m = max(m, nearby[bin + d])
                if (p <= m) continue
                for (d in
                    intArrayOf(-53, -45, 165, 172, 179, 186, 193, 200, 214, 221, 228, 235, 242, 249))
                    m = max(m, spread[(index + d) and 255][bin - 1])
                if (p <= m) continue
                val mag = mag(p)
                val before = mag(center[bin - 1])
                val after = mag(center[bin + 1])
                val curve = 2 * mag - before - after
                if (curve <= 0) continue
                val corrected = (bin * 64 + (after - before) * 32 / curve).toInt()
                val hz = corrected * 16000.0 / 131072
                val band =
                    if (hz > 250 && hz < 520) 0
                    else if (hz >= 520 && hz < 1450) 1
                    else if (hz >= 1450 && hz < 3500) 2
                    else if (hz >= 3500 && hz < 5500) 3
                    else -1
                if (band >= 0) bands[band].add(Peak(frames - 46, mag.toInt(), corrected))
            }
            off += 128
        }
        val contents = ByteArrayOutputStream()
        for (b in 0 until 4) {
            if (bands[b].isEmpty()) continue
            val peaks = ByteArrayOutputStream()
            var prev = 0
            for (p in bands[b]) {
                if (p.frame - prev >= 255) {
                    peaks.write(255)
                    putInt(peaks, p.frame)
                    prev = p.frame
                }
                peaks.write(p.frame - prev)
                putShort(peaks, p.magnitude)
                putShort(peaks, p.bin)
                prev = p.frame
            }
            putInt(contents, 0x60030040 + b)
            putInt(contents, peaks.size())
            val bytes = peaks.toByteArray()
            contents.write(bytes, 0, bytes.size)
            while ((contents.size() and 3) != 0) contents.write(0)
        }
        val out =
            ByteBuffer.allocate(56 + contents.size()).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0xCAFE2580.toInt()).putInt(0).putInt(contents.size() + 8).putInt(0x94119C00.toInt())
        out.putInt(0)
            .putInt(0)
            .putInt(0)
            .putInt(3 shl 27)
            .putInt(0)
            .putInt(0)
            .putInt(samples.size / 128 * 128 + 3840)
            .putInt((15 shl 19) + 0x40000)
        out.putInt(0x40000000).putInt(contents.size() + 8).put(contents.toByteArray())
        val crc = CRC32()
        crc.update(out.array(), 8, out.capacity() - 8)
        out.putInt(4, crc.value.toInt())
        return "data:audio/vnd.shazam.sig;base64," +
            Base64.getEncoder().encodeToString(out.array())
    }

    private fun mag(p: Double): Double = ln(max(1.0 / 64, p)) * 1477.3 + 6144

    private fun putInt(s: ByteArrayOutputStream, x: Int) {
        for (i in 0 until 4) s.write((x ushr (8 * i)) and 255)
    }

    private fun putShort(s: ByteArrayOutputStream, x: Int) {
        s.write(x and 255)
        s.write((x ushr 8) and 255)
    }

    private fun fft(re: DoubleArray, im: DoubleArray) {
        var j = 0
        for (i in 1 until N) {
            var bit = N shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val t = re[i]
                re[i] = re[j]
                re[j] = t
            }
        }
        var len = 2
        while (len <= N) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var start = 0
            while (start < N) {
                var r = 1.0
                var v = 0.0
                for (jj in 0 until len / 2) {
                    val a = start + jj
                    val b = a + len / 2
                    val br = re[b] * r - im[b] * v
                    val bi = re[b] * v + im[b] * r
                    re[b] = re[a] - br
                    im[b] = im[a] - bi
                    re[a] += br
                    im[a] += bi
                    val next = r * wr - v * wi
                    v = r * wi + v * wr
                    r = next
                }
                start += len
            }
            len = len shl 1
        }
    }
}
