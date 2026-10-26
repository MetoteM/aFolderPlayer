package dev.alex.folderplayer

import org.junit.Test

class DocumentTranslationTest {
    private fun check(b: Boolean) {
        if (!b) throw AssertionError()
    }

    @Test
    fun scenarios() {
        val p =
            DocumentTranslation.paragraphs("Verse.\nSecond.\r\n\r\nChorus.\n\nVerse.\nSecond.")
        check(p.size == 3)
        check(p[0] == p[2])
        check(CompletenessRules.units(p[0]).size == 2)
        check(
            DocumentTranslation.assemble(listOf("Куплет", "Припев", "Куплет")) ==
                "Куплет\n\nПрипев\n\nКуплет"
        )
        check(DocumentTranslation.paragraphs("One\nline").size == 1)
        check(CompletenessRules.units("One\nline") == listOf("One line"))
        check(DocumentTranslation.paragraphs("\n\nA.\n\n") == listOf("", "A.", ""))
        check(DocumentTranslation.assemble(listOf("A", "", "B")) == "A\n\n\n\nB")
        println("PASS: 8 document segmentation, chorus, assembly and empty paragraph checks")
    }
}
