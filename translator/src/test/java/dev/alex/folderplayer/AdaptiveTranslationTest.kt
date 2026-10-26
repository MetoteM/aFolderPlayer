package dev.alex.folderplayer

import java.io.IOException
import java.util.Arrays
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AdaptiveTranslationTest {
    @Test
    fun retryRetainsAllWords() {
        val calls = AtomicInteger()
        val source = "first second third fourth fifth sixth"
        assertEquals(
            source,
            OfflineTranslator.adaptiveRetry(
                source,
                { false },
                0,
                { text ->
                    if (calls.getAndIncrement() == 0)
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    text
                },
            ),
        )
        assertEquals(3, calls.get())
    }

    @Test
    fun decomposesAgainAndNeverRepeatsSuccessfulPiece() {
        val calls = HashMap<String, Int>()
        val source = "one two three four five six seven eight"
        assertEquals(
            source,
            OfflineTranslator.adaptiveRetry(
                source,
                { false },
                0,
                { text ->
                    calls.merge(text, 1, Integer::sum)
                    if (text.split(" ").size > 2)
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    text
                },
            ),
        )
        assertEquals(7, calls.size)
        assertTrue(calls.values.all { it == 1 })
    }

    @Test
    fun thirdLevelRecoversTwoWordFragment() {
        assertEquals(
            "one two",
            OfflineTranslator.adaptiveRetry(
                "one two",
                { false },
                0,
                { text ->
                    if (text.contains(" "))
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    text
                },
            ),
        )
    }

    @Test
    fun failedRetryDoesNotReturnPartialDecoderOutput() {
        val calls = AtomicInteger()
        try {
            OfflineTranslator.adaptiveRetry(
                "one two three four five six seven eight nine ten",
                { false },
                0,
                { text ->
                    calls.incrementAndGet()
                    throw OfflineTranslator.IncompleteTranslation("incomplete")
                },
            )
            fail()
        } catch (expected: OfflineTranslator.IncompleteTranslation) {}
        assertTrue(calls.get() <= 4)
    }

    @Test
    fun cancellationPreventsAnyDecode() {
        val calls = AtomicInteger()
        try {
            OfflineTranslator.adaptiveRetry(
                "one two",
                { true },
                0,
                { text ->
                    calls.incrementAndGet()
                    text
                },
            )
            fail()
        } catch (expected: InterruptedException) {}
        assertEquals(0, calls.get())
    }

    @Test
    fun cancellationBetweenPiecesStopsImmediately() {
        val calls = AtomicInteger()
        try {
            OfflineTranslator.adaptiveRetry(
                "one two three four",
                { calls.get() >= 2 },
                0,
                { text ->
                    if (calls.incrementAndGet() == 1)
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    text
                },
            )
            fail()
        } catch (expected: InterruptedException) {}
        assertEquals(2, calls.get())
    }

    @Test
    fun resourceErrorsAreNotRetried() {
        val calls = AtomicInteger()
        try {
            OfflineTranslator.adaptiveRetry(
                "one two three four",
                { false },
                0,
                { text ->
                    calls.incrementAndGet()
                    throw IOException("missing model")
                },
            )
            fail()
        } catch (expected: IOException) {
            assertEquals("missing model", expected.message)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun splitPrefersPunctuationAndPreservesUnicode() {
        assertEquals(
            Arrays.asList("First sentence.", "Another sentence"),
            OfflineTranslator.splitRecovery("First sentence. Another sentence"),
        )
        assertEquals(Arrays.asList("你好", "世界"), OfflineTranslator.splitRecovery("你好世界"))
        assertEquals(listOf("word"), OfflineTranslator.splitRecovery("word"))
    }

    @Test
    fun depthLimitAndAttemptBudgetAreFinite() {
        val calls = AtomicInteger()
        try {
            OfflineTranslator.adaptiveRetry(
                "one two",
                { false },
                3,
                { text ->
                    calls.incrementAndGet()
                    throw OfflineTranslator.IncompleteTranslation("incomplete")
                },
            )
            fail()
        } catch (expected: OfflineTranslator.IncompleteTranslation) {}
        assertEquals(1, calls.get())
        val budget = OfflineTranslator.RetryBudget()
        for (i in 0 until 14) {
            assertTrue(budget.available())
            budget.use()
        }
        assertFalse(budget.available())
    }

    @Test
    fun partialResultRetainsOtherUnitsBlankLinesAndRepeatedFailures() {
        val failedCalls = AtomicInteger()
        val result =
            OfflineTranslator.translateUnits(
                "Good.\nBad.\n\nGood.\nBad.\nLast.",
                { false },
                { _, _ -> },
                true,
                { text -> listOf(text) },
                { text ->
                    if (text == "Bad.") {
                        failedCalls.incrementAndGet()
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    }
                    "RU $text"
                },
            )
        assertEquals("RU Good.\nBad.\n\nRU Good.\nBad.\nRU Last.", result.text)
        assertEquals(Arrays.asList(1, 4), result.failedLines)
        assertEquals(1, failedCalls.get())
    }

    @Test
    fun strictProtocolStillRejectsPartialResult() {
        try {
            OfflineTranslator.translateUnits(
                "Good.\nBad.",
                { false },
                { _, _ -> },
                false,
                { text -> listOf(text) },
                { text ->
                    if (text == "Bad.")
                        throw OfflineTranslator.IncompleteTranslation("incomplete")
                    "RU $text"
                },
            )
            fail()
        } catch (expected: OfflineTranslator.IncompleteTranslation) {}
    }

    @Test
    fun cancelledPartialJobNeverPublishesSuccess() {
        val cancel = AtomicBoolean()
        try {
            OfflineTranslator.translateUnits(
                "Good.\nLast.",
                { cancel.get() },
                { _, _ -> cancel.set(true) },
                true,
                { text -> listOf(text) },
                { text -> "RU $text" },
            )
            fail()
        } catch (expected: InterruptedException) {}
    }

    @Test
    fun sharedExtraWorkBudgetStopsFurtherDecomposition() {
        val spent = longArrayOf(TimeUnit.SECONDS.toNanos(120))
        assertFalse(OfflineTranslator.RetryBudget(spent).available())
    }
}
