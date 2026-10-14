package dev.alex.folderplayer

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** One engine request batches unique blocks and sentence units, retaining repeats on assembly. */
internal class DocumentEngine
@JvmOverloads
constructor(private val context: Context, val model: String = TranslationClient(context).modelId) {
    val incompleteUnits = linkedSetOf<String>()
    var recoveredUnits: Map<String, String> = emptyMap()
    val completedUnits = linkedMapOf<String, String>()

    fun interface Progress {
        fun update(done: Int, total: Int)
    }

    fun translate(
        paragraphs: List<String>,
        cancel: AtomicBoolean,
        progress: Progress,
    ): Map<String, String> {
        return translateUnits(DocumentTranslation.requestUnits(paragraphs, true), cancel, progress)
    }

    fun translateSentences(source: String, cancel: AtomicBoolean, progress: Progress): String {
        require(source.length <= 20000 && source.split("\n").size <= 500) {
            "Перевод поддерживает до 20 000 символов и 500 строк"
        }
        val paragraphs = DocumentTranslation.paragraphs(source)
        val translated =
            translateUnits(DocumentTranslation.requestUnits(paragraphs, false), cancel, progress)
        if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        return DocumentTranslation.assembleSentences(paragraphs, translated)
    }

    private fun translateUnits(
        units: LinkedHashSet<String>,
        cancel: AtomicBoolean,
        progress: Progress,
    ): Map<String, String> {
        check(context.getSharedPreferences("extensions", 0).getBoolean("enabled", false)) {
            "Включи перевод в расширениях плеера"
        }
        val source = units.joinToString("\n")
        require(source.any { it.isLetter() }) { "В оригинале нет слов для перевода" }
        require(
            !model.startsWith("opus-en-ru-") ||
                source.count { it in 'А'..'я' || it == 'ё' || it == 'Ё' } <=
                    source.count { it in 'A'..'Z' || it in 'a'..'z' }
        ) {
            "Этот пакет переводит с английского на русский"
        }
        require(source.length <= 20000 && units.size <= 500) {
            "Слишком большой текст для перевода: выбери более короткий оригинал"
        }
        completedUnits.clear()
        completedUnits.putAll(
            recoveredUnits.filter { (unit, value) ->
                unit in units && value.isNotBlank() && !value.contains("\n")
            }
        )
        val pending = units.filter { it !in completedUnits }
        incompleteUnits.clear()
        if (pending.isEmpty()) return completedUnits + ("" to "")
        val client = TranslationClient(context)
        val outcome =
            client.translateRecovering(pending.joinToString("\n"), cancel, model) { done, total ->
                progress.update(done, total)
            }
        val translated = outcome.text.split("\n")
        val originals = pending
        outcome.failedLines.forEach { incompleteUnits.add(originals[it]) }
        if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        check(translated.size == pending.size && translated.all { it.isNotBlank() }) {
            "Движок вернул неполный результат"
        }
        pending.forEachIndexed { index, unit ->
            if (index !in outcome.failedLines) completedUnits[unit] = translated[index]
        }
        return completedUnits +
            pending
                .zip(
                    translated.mapIndexed { index, value ->
                        if (index in outcome.failedLines) "⟦Не переведено⟧ $value" else value
                    }
                )
                .toMap() +
            ("" to "")
    }
}
