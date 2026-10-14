package dev.alex.folderplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class TranslationUi080Test {
    private fun views(v: View): List<View> =
        listOf(v) +
            if (v is ViewGroup) (0 until v.childCount).flatMap { views(v.getChildAt(it)) }
            else emptyList()

    @Test
    fun completedDocumentAndManualChoicesSurviveRecreation() {
        val app = RuntimeEnvironment.getApplication()
        val intent =
            Intent(app, DocumentActivity::class.java)
                .putExtra("source", "Hello.")
                .putExtra("translationModel", TranslationClient.MODEL)
        val row =
            JSONObject()
                .put("source", "Hello.")
                .put("model", TranslationClient.MODEL)
                .put("blocks", JSONArray().put("Привет."))
                .put("sentences", JSONArray().put("Здравствуйте."))
                .put("warnings", JSONArray().put(""))
                .put("choices", JSONArray().put("block"))
        val state = Bundle().apply { putString("completed", row.toString()) }
        val host =
            Robolectric.buildActivity(DocumentActivity::class.java, intent)
                .create(state)
                .start()
                .resume()
        try {
            val activity = host.get()
            val saved = Bundle()
            host.saveInstanceState(saved)
            assertEquals(
                "block",
                JSONObject(saved.getString("completed")!!).getJSONArray("choices").getString(0),
            )
            val buttons = views(activity.window.decorView).filterIsInstance<Button>()
            assertTrue(buttons.any { it.text == "Дополнительно" })
            assertFalse(buttons.any { it.text == "Все фрагменты" })
            assertTrue(buttons.any { it.text == "Перевести заново" })
        } finally {
            host.pause().stop().destroy()
        }
    }

    @Test
    fun languagePickerShowsOneEnglishChoiceAndNoGlobalChange() {
        val models =
            TranslationLanguages.choices(
                listOf(
                    TranslationClient.MODEL to "v1",
                    "opus-en-ru-v2" to "v2",
                    "opus-de-ru-v1" to "German",
                )
            )
        assertEquals(listOf("Английский", "Немецкий"), models.map { it.second })
        assertEquals("opus-en-ru-v2", models.first().first)
    }

    @Test
    fun completedRetranslationReplacesOldResultButRepeatedDeliveryKeepsManualChoices() {
        val app = RuntimeEnvironment.getApplication()
        val intent =
            Intent(app, DocumentActivity::class.java)
                .putExtra("source", "Hello.")
                .putExtra("translationModel", TranslationClient.MODEL)
        val row =
            JSONObject()
                .put("source", "Hello.")
                .put("model", TranslationClient.MODEL)
                .put("blocks", JSONArray().put("Старый блок."))
                .put("sentences", JSONArray().put("Старый перевод."))
                .put("warnings", JSONArray().put(""))
                .put("choices", JSONArray().put("block"))
        val host =
            Robolectric.buildActivity(DocumentActivity::class.java, intent)
                .create(Bundle().apply { putString("completed", row.toString()) })
                .start()
                .resume()
        try {
            val value =
                DocumentResult(
                    "Hello.",
                    TranslationClient.MODEL,
                    listOf("Новый блок."),
                    listOf("Новый перевод."),
                    listOf(""),
                )
            val state = RetainedOperation.Snapshot("Hello.", "", false, value, null)
            val deliver =
                DocumentActivity::class
                    .java
                    .getDeclaredMethod("showOperation", RetainedOperation.Snapshot::class.java)
                    .apply { isAccessible = true }
            deliver.invoke(host.get(), state)
            val parts =
                DocumentActivity::class
                    .java
                    .getDeclaredField("parts")
                    .apply { isAccessible = true }
                    .get(host.get()) as List<*>
            assertEquals(listOf("Новый перевод."), parts)
            val choices =
                DocumentActivity::class
                    .java
                    .getDeclaredField("useBlock")
                    .apply { isAccessible = true }
                    .get(host.get()) as BooleanArray
            choices[0] = true
            deliver.invoke(host.get(), state)
            assertTrue(
                (DocumentActivity::class
                    .java
                    .getDeclaredField("useBlock")
                    .apply { isAccessible = true }
                    .get(host.get()) as BooleanArray)[0]
            )
        } finally {
            host.pause().stop().destroy()
        }
    }
}
