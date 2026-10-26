package dev.alex.folderplayer

import org.junit.Test

class CompletenessRulesTest {
    private fun check(ok: Boolean) {
        if (!ok) throw AssertionError()
    }

    @Test
    fun scenarios() {
        check(
            CompletenessRules.units("Dr. Smith opened the door.\nMrs. Green answered the phone.")
                .size == 2
        )
        check(
            CompletenessRules.units("The box weighed 2.5 kilograms.\nI carried it upstairs.")
                .size == 2
        )
        check(
            CompletenessRules.units("She asked me\nto close the window.") ==
                listOf("She asked me to close the window.")
        )
        check(
            CompletenessRules.units("I did not open the letter\nnor throw it away.").size == 1
        )
        check(
            CompletenessRules.units("My mother gave me a letter\nthat I will keep forever.")
                .size == 1
        )
        check(
            CompletenessRules.units("I came home.\nI came home.") ==
                listOf("I came home.", "I came home.")
        )
        check(
            CompletenessRules.units("First.\r\n\r\nSecond.") ==
                listOf("First.", "", "Second.")
        )
        check(
            CompletenessRules.warning(
                "Любовь матери к сыну не разглашается.",
                listOf("Любовь матери к сыну не разглашается.", "Помоги мне быть собой."),
            ).isNotEmpty()
        )
        check(
            CompletenessRules.warning(
                "Я понесла его наверху.",
                listOf("Коробка весила 2,5 килограмма.", "Я понесла его наверх."),
            ).isNotEmpty()
        )
        check(
            CompletenessRules.warning("Я согласился, я сказал да.", listOf("Я согласился.", "Я сказал да."))
                .isEmpty()
        )
        check(
            CompletenessRules.warning("Я вернулся домой.", listOf("Я вернулся домой.", "Я вернулся домой."))
                .isEmpty()
        )
        check(CompletenessRules.warning("One.", listOf("One.")).isEmpty())
        println("PASS: 12 boundary, negation scope, repetition, paragraph, warning and control checks")
    }
}
