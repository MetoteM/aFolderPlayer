package dev.alex.folderplayer

import java.util.Collections
import java.util.HashMap
import java.util.LinkedHashSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OrdinaryTranslationTest {
    @Test
    fun wrappedFragmentsAreSentAsTheSameContextAsFullText() {
        val paragraphs =
            DocumentTranslation.paragraphs("Hold\nthis promise\nclose to your heart.\n\nRemember me.")
        val ordinary = DocumentTranslation.requestUnits(paragraphs, false)
        assertEquals(
            LinkedHashSet(listOf("Hold this promise close to your heart.", "Remember me.")),
            ordinary,
        )
        assertTrue(DocumentTranslation.requestUnits(paragraphs, true).containsAll(ordinary))
        assertFalse(ordinary.contains("Hold"))
    }

    @Test
    fun repeatedChorusAndEmptyParagraphsSurviveDeduplication() {
        val paragraphs = DocumentTranslation.paragraphs("Stay.\nStay.\n\nRemember\nme.\n\nStay.\nStay.")
        assertEquals(2, DocumentTranslation.requestUnits(paragraphs, false).size)
        val translated = HashMap<String, String>()
        translated["Stay."] = "Останься."
        translated["Remember me."] = "Помни меня."
        translated[""] = ""
        assertEquals(
            "Останься.\nОстанься.\n\nПомни меня.\n\nОстанься.\nОстанься.",
            DocumentTranslation.assembleSentences(paragraphs, translated),
        )
    }

    @Test
    fun ordinaryAssemblyMatchesFullTextDefault() {
        val paragraphs = DocumentTranslation.paragraphs("First sentence.\nSecond sentence.\n\n第三句。第四句。")
        val translated = HashMap<String, String>()
        translated[""] = ""
        for (unit in DocumentTranslation.requestUnits(paragraphs, true)) translated[unit] = "Перевод $unit"
        val selected = ArrayList<String>()
        for (paragraph in paragraphs) {
            val parts = ArrayList<String>()
            for (unit in CompletenessRules.units(paragraph)) parts.add(translated[unit]!!)
            selected.add(parts.joinToString("\n"))
        }
        assertEquals(
            DocumentTranslation.assemble(selected),
            DocumentTranslation.assembleSentences(paragraphs, translated),
        )
    }

    @Test
    fun missingTranslationCannotBecomeSavedText() {
        try {
            DocumentTranslation.assembleSentences(listOf("One. Two."), Collections.singletonMap("One.", "Один."))
            fail()
        } catch (expected: IllegalArgumentException) {}
    }
}
