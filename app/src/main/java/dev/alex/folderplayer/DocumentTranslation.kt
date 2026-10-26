package dev.alex.folderplayer

/** Paragraph-level document translation helpers. */
object DocumentTranslation {
    @JvmStatic
    fun paragraphs(source: String): List<String> =
        source.replace("\r", "").split(Regex("\\n[ \\t]*\\n")).let { it.toList() }

    @JvmStatic
    fun assemble(translated: List<String>): String = translated.joinToString("\n\n")

    @JvmStatic
    fun requestUnits(paragraphs: List<String>, compareBlocks: Boolean): LinkedHashSet<String> {
        val units = LinkedHashSet<String>()
        for (paragraph in paragraphs) {
            if (compareBlocks) units.add(CompletenessRules.block(paragraph))
            units.addAll(CompletenessRules.units(paragraph))
        }
        units.remove("")
        return units
    }

    @JvmStatic
    fun assembleSentences(paragraphs: List<String>, translations: Map<String, String>): String {
        val result = ArrayList<String>()
        for (paragraph in paragraphs) {
            val parts = ArrayList<String>()
            for (unit in CompletenessRules.units(paragraph)) {
                val value =
                    translations[unit]
                        ?: throw IllegalArgumentException("Движок вернул неполный результат")
                if (unit.isNotEmpty() && value.trim().isEmpty())
                    throw IllegalArgumentException("Движок вернул неполный результат")
                parts.add(value)
            }
            result.add(parts.joinToString("\n"))
        }
        return assemble(result)
    }
}
