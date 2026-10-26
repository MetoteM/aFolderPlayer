package dev.alex.folderplayer

import java.util.Locale
import java.util.regex.Pattern

/** Experimental English sentence boundaries; blank paragraphs are preserved. */
object CompletenessRules {
    private val TITLES =
        setOf("mr", "mrs", "ms", "dr", "prof", "st", "jr", "sr", "etc", "vs")

    @JvmStatic
    fun units(source: String): List<String> {
        val result = ArrayList<String>()
        val paragraphs = source.replace("\r", "").split(Regex("\\n[ \\t]*\\n"))
        for ((p, paragraph) in paragraphs.withIndex()) {
            if (p > 0) result.add("")
            val lines = paragraph.split("\n")
            val pending = StringBuilder()
            var i = 0
            while (i < lines.size) {
                val line = lines[i].trim()
                var end = i + 1
                while (line.isNotEmpty() && end < lines.size && lines[end].trim() == line) end++
                if (end - i > 1) {
                    plainUnits(pending.toString(), result)
                    pending.setLength(0)
                    for (j in i until end) plainUnits(line, result)
                } else {
                    if (pending.length > 0) pending.append(' ')
                    pending.append(line)
                }
                i = end
            }
            plainUnits(pending.toString(), result)
        }
        return result
    }

    private fun plainUnits(source: String, result: MutableList<String>) {
        val text = source.replace(Regex("\\s+"), " ").trim()
        if (text.isEmpty()) return
        val chinese = Pattern.compile("[。！？]+[\"”’」』]?\\s*").matcher(text)
        var chineseStart = 0
        if (chinese.find()) {
            do {
                result.add(text.substring(chineseStart, chinese.end()).trim())
                chineseStart = chinese.end()
            } while (chinese.find())
            if (chineseStart < text.length) plainUnits(text.substring(chineseStart), result)
            return
        }
        val m = Pattern.compile("[.!?]+[\"”']?\\s+(?=[A-Z])").matcher(text)
        var start = 0
        while (m.find()) {
            val word = Pattern.compile("([A-Za-z]+)$").matcher(text.substring(0, m.start()))
            if (
                word.find() &&
                    (word.group(1)!!.length == 1 ||
                        word.group(1)!!.lowercase(Locale.ROOT) in TITLES)
            )
                continue
            val end = m.start() + m.group().trim().length
            result.add(text.substring(start, end))
            start = m.end()
        }
        result.add(text.substring(start))
    }

    @JvmStatic
    fun warning(
        block: String,
        translated: List<String>,
        sourceUnits: List<String>,
    ): String {
        if (translated.size != sourceUnits.size)
            throw IllegalArgumentException("Parts do not match")
        val counts = HashMap<String, Int>()
        val values = HashMap<String, String>()
        for (i in sourceUnits.indices) {
            val source = sourceUnits[i].trim()
            if (source.isEmpty()) continue
            counts[source] = (counts[source] ?: 0) + 1
            values[source] = normalize(translated[i])
        }
        val normalizedBlock = " " + normalize(block) + " "
        for (source in counts.keys) {
            if (counts[source]!! > 1) {
                val value = values[source] ?: continue
                if (value.isEmpty()) continue
                val needle = " $value "
                var seen = 0
                var start = 0
                var pos: Int
                while (normalizedBlock.indexOf(needle, start).also { pos = it } >= 0) {
                    seen++
                    start = pos + needle.length - 1
                }
                if (seen < counts[source]!!)
                    return "Возможно, блок потерял повтор: одинаковая исходная часть встречается в нём реже." +
                        " Формулировка блока могла измениться — сравни варианты."
            }
        }
        return warning(block, translated)
    }

    @JvmStatic
    fun block(source: String): String = source.replace(Regex("\\s+"), " ").trim()

    @JvmStatic
    fun normalize(s: String): String {
        val m = Pattern.compile("[\\p{L}\\p{N}_]+").matcher(s.lowercase(Locale.ROOT))
        val w = ArrayList<String>()
        while (m.find()) w.add(m.group())
        return w.joinToString(" ")
    }

    @JvmStatic
    fun warning(block: String, translated: List<String>): String {
        val parts = ArrayList<String>()
        for (s in translated) if (s.trim().isNotEmpty()) parts.add(s)
        if (parts.size < 2) return ""
        var separate = 0
        val distinct = HashSet<String>()
        for (s in parts) {
            val n = normalize(s)
            separate += if (n.isEmpty()) 0 else n.split(" ").size
            distinct.add(n)
        }
        val b = normalize(block)
        val count = if (b.isEmpty()) 0 else b.split(" ").size
        if (distinct.size > 1 && b in distinct)
            return "Возможно, блок пропустил часть текста: он совпал с переводом одного предложения."
        if (separate >= 8 && count < separate * .60)
            return "Возможно, блок пропустил часть текста: он заметно короче перевода предложений."
        return ""
    }
}
