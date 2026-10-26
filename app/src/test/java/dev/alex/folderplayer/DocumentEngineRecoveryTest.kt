package dev.alex.folderplayer

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DocumentEngineRecoveryTest {
    @Test
    fun checkpointRestoresRepeatedUnitsInOriginalOrderWithoutEngine() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("extensions", 0).edit().putBoolean("enabled", true).commit()
        val engine = DocumentEngine(context, "opus-en-ru-v2")
        engine.recoveredUnits =
            mapOf(
                "Stay." to "Останься.",
                "Remember me." to "Помни меня.",
                "Other song." to "Другой трек.",
            )
        val result =
            engine.translateSentences(
                "Stay.\nStay.\n\nRemember me.\n\nStay.",
                AtomicBoolean(false),
            ) { _, _ ->
                fail("No engine request needed")
            }
        assertEquals("Останься.\nОстанься.\n\nПомни меня.\n\nОстанься.", result)
        assertEquals(2, engine.completedUnits.size)
        assertTrue(engine.incompleteUnits.isEmpty())
    }

    @Test
    fun checkpointCannotSilentlyFillMissingOrMultilineUnit() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("extensions", 0).edit().putBoolean("enabled", true).commit()
        val engine = DocumentEngine(context, "opus-en-ru-v2")
        engine.recoveredUnits =
            mapOf("Stay." to "Останься.\nПосторонняя строка", "Other song." to "Посторонняя песня")
        try {
            engine.translateSentences("Stay.", AtomicBoolean(false)) { _, _ -> }
            fail("Missing unit must be translated")
        } catch (_: TranslationClient.MissingEngine) {}
        assertTrue(engine.completedUnits.isEmpty())
    }

    @Test
    fun recoveryPayloadKeepsExactFailedIndicesAndRejectsInvalidShape() {
        val client = TranslationClient(RuntimeEnvironment.getApplication())
        val result =
            client.decodeRecovered(
                """{"text":"Один.\nBad.\nТри.","failedLines":[1]}""",
                "One.\nBad.\nThree.",
            )
        assertEquals(setOf(1), result.failedLines)
        for (raw in
            listOf(
                """{"text":"Один.","failedLines":[]}""",
                """{"text":"Один.\nДва.","failedLines":[2]}""",
            )) {
            try {
                client.decodeRecovered(raw, "One.\nTwo.")
                fail("Invalid response must be rejected")
            } catch (_: IllegalStateException) {}
        }
    }
}
