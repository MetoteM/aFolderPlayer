package dev.alex.folderplayer

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Ten independent peaking filters per channel. No automatic attenuation. */
class DspEqualizer {
    class Settings(enabled: Boolean, preampDb: Double, gains: DoubleArray) {
        val enabled: Boolean = enabled
        val preampDb: Double = finiteClamp(preampDb, -12.0, 0.0)
        private val gains: DoubleArray

        init {
            this.gains = DoubleArray(FREQUENCIES.size)
            for (i in this.gains.indices)
                this.gains[i] = finiteClamp(if (i < gains.size) gains[i] else 0.0, -12.0, 12.0)
        }

        fun sameAs(other: Settings?): Boolean =
            other != null &&
                enabled == other.enabled &&
                preampDb == other.preampDb &&
                gains.contentEquals(other.gains)

        fun gain(i: Int): Double = gains[i]

        fun isBypass(): Boolean {
            if (!enabled) return true
            if (preampDb != 0.0) return false
            for (g in gains) if (g != 0.0) return false
            return true
        }

        companion object {
            private fun finiteClamp(x: Double, min: Double, max: Double): Double =
                if (x.isFinite()) maxOf(min, minOf(max, x)) else 0.0
        }
    }

    private val coefficients = Array(FREQUENCIES.size) { DoubleArray(5) }
    private var z1 = Array(0) { DoubleArray(0) }
    private var z2 = Array(0) { DoubleArray(0) }
    private var settings = Settings(false, 0.0, DoubleArray(FREQUENCIES.size))
    private var sampleRate = 0
    private var gain = 1.0
    private var bypass = true

    fun configure(sampleRate: Int, channels: Int, settings: Settings) {
        if (sampleRate <= 0 || channels <= 0)
            throw IllegalArgumentException("Invalid audio format")
        this.sampleRate = sampleRate
        z1 = Array(channels) { DoubleArray(FREQUENCIES.size) }
        z2 = Array(channels) { DoubleArray(FREQUENCIES.size) }
        setSettings(settings)
    }

    fun setSettings(settings: Settings) {
        this.settings = settings
        bypass = settings.isBypass()
        gain = 10.0.pow(settings.preampDb / 20)
        for (i in FREQUENCIES.indices) {
            val c = coefficients[i]
            if (settings.gain(i) == 0.0 || FREQUENCIES[i] >= sampleRate * 0.49) {
                c[0] = 1.0
                c[1] = 0.0
                c[2] = 0.0
                c[3] = 0.0
                c[4] = 0.0
                continue
            }
            val a = 10.0.pow(settings.gain(i) / 40)
            val w = 2 * PI * FREQUENCIES[i] / sampleRate
            val alpha = sin(w) / (2 * 1.0) // Q = 1
            val cosV = cos(w)
            val a0 = 1 + alpha / a
            c[0] = (1 + alpha * a) / a0
            c[1] = -2 * cosV / a0
            c[2] = (1 - alpha * a) / a0
            c[3] = -2 * cosV / a0
            c[4] = (1 - alpha / a) / a0
        }
        reset()
    }

    fun isBypass(): Boolean = bypass

    fun reset() {
        for (row in z1) java.util.Arrays.fill(row, 0.0)
        for (row in z2) java.util.Arrays.fill(row, 0.0)
    }

    fun process(sample: Double, channel: Int): Double {
        if (isBypass()) return sample
        var x = sample * gain
        for (band in FREQUENCIES.indices) {
            val c = coefficients[band]
            val y = c[0] * x + z1[channel][band]
            z1[channel][band] = c[1] * x - c[3] * y + z2[channel][band]
            z2[channel][band] = c[2] * x - c[4] * y
            x = y
        }
        return x
    }

    companion object {
        @JvmField
        val FREQUENCIES =
            doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    }
}
