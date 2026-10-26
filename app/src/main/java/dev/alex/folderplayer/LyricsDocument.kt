package dev.alex.folderplayer

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.regex.Pattern

/** Simple LRC: multiple timestamps, fractions, global offset, stable simultaneous cues. */
class LyricsDocument(raw: String) {
    class Cue(val timeMs: Long, val text: String)

    val cues: List<Cue>
    val plain: String

    init {
        val value = if (raw.startsWith("\uFEFF")) raw.substring(1) else raw
        if (value.toByteArray(StandardCharsets.UTF_8).size > MAX_BYTES)
            throw IllegalArgumentException("Текст больше 512 КиБ")
        val lines = value.replace("\r\n", "\n").replace('\r', '\n').split("\n")
        if (lines.size > 4000)
            throw IllegalArgumentException("Слишком много строк (максимум 4000)")
        var offset = 0L
        val offsets = OFFSET.matcher(value)
        while (offsets.find()) offset = offsets.group(1)!!.toLong()
        val result = ArrayList<Cue>()
        var expanded = 0L
        for (line in lines) {
            val m = TIME.matcher(line)
            val times = ArrayList<Long>()
            var end = 0
            while (m.find() && line.substring(end, m.start()).trim().isEmpty()) {
                val fraction = m.group(3)
                val ms = if (fraction == null) 0L else (fraction + "000").substring(0, 3).toLong()
                times.add(m.group(1)!!.toLong() * 60000 + m.group(2)!!.toLong() * 1000 + ms)
                end = m.end()
            }
            if (times.size + result.size > 8000)
                throw IllegalArgumentException("Слишком много меток времени")
            val body = line.substring(end).trim()
            expanded += times.size * (body.length + 1L)
            if (expanded > MAX_BYTES)
                throw IllegalArgumentException("Развёрнутый LRC слишком большой")
            for (time in times) result.add(Cue(maxOf(0, time - offset), body))
        }
        result.sortBy { it.timeMs }
        cues = java.util.Collections.unmodifiableList(result)
        plain = if (result.isEmpty()) value else result.joinToString("\n") { it.text }
    }

    fun activeIndex(position: Long): Int {
        var lo = 0
        var hi = cues.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (cues[mid].timeMs <= position) lo = mid + 1 else hi = mid
        }
        return lo - 1
    }

    fun groupStart(index: Int): Int {
        var i = index
        while (i > 0 && cues[i - 1].timeMs == cues[i].timeMs) i--
        return i
    }

    companion object {
        const val MAX_BYTES: Int = 512 * 1024
        private val TIME = Pattern.compile("\\[(\\d{1,6}):([0-5]\\d)(?:[.:](\\d{1,3}))?\\]")
        private val OFFSET = Pattern.compile("(?im)^\\s*\\[offset:([+-]?\\d{1,9})\\]\\s*$")

        @JvmStatic
        fun timestamp(timeMs: Long): String {
            val t = maxOf(0, timeMs)
            return String.format(Locale.ROOT, "[%02d:%02d.%03d]", t / 60000, (t / 1000) % 60, t % 1000)
        }

        @JvmStatic
        fun key(uri: String): String {
            val b =
                MessageDigest.getInstance("SHA-256").digest(uri.toByteArray(StandardCharsets.UTF_8))
            val s = StringBuilder()
            for (x in b) s.append(String.format(Locale.ROOT, "%02x", x.toInt() and 255))
            return s.toString()
        }
    }
}
