package dev.alex.folderplayer

import org.junit.Test

class DocumentRepeatTest {
    private fun check(b: Boolean) {
        if (!b) throw AssertionError()
    }

    @Test
    fun scenarios() {
        val src = CompletenessRules.units("I came home\nI came home")
        check(src == listOf("I came home", "I came home"))
        val out = listOf("Я вернулся домой.", "Я вернулся домой.")
        check(CompletenessRules.warning("Я вернулся домой.", out, src).isNotEmpty())
        check(CompletenessRules.warning("Я вернулся домой. Я вернулся домой.", out, src).isEmpty())
        check(CompletenessRules.warning("Я вернулся домой домой.", out, src).length > 0)
        check(CompletenessRules.units("She asked me\nto close the window.").size == 1)
        check(CompletenessRules.units("I did not open the letter\nnor throw it away.").size == 1)
        check(
            CompletenessRules.units(
                "Before sunrise.\nGo home\nGo home\nShe asked me\nto close the window."
            ) ==
                listOf(
                    "Before sunrise.",
                    "Go home",
                    "Go home",
                    "She asked me to close the window.",
                )
        )
        check(CompletenessRules.units("Go home\nGo home\nGo home").size == 3)
        check(
            CompletenessRules.warning("Да.", listOf("Да.", "Да."), listOf("Yes.", "Indeed."))
                .isEmpty()
        )
        check(CompletenessRules.warning("Я пришёл домой дважды.", out, src).isNotEmpty())
        check(CompletenessRules.units("Go home\r\nGo home").size == 2)
        check(
            CompletenessRules.warning("Я вернулся домой.", out, listOf("I came home.", "I came home."))
                .isNotEmpty()
        )
        println(
            "PASS: 12 repeated-source, no-punctuation, preserved-repeat, paraphrase-warning and dependency checks"
        )
    }
}
