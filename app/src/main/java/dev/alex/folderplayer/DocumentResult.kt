package dev.alex.folderplayer

import android.content.Context
import java.util.LinkedHashSet
import java.util.UUID

/** Full comparison data without Activity references; no partial result is published. */
internal class DocumentResult(
    val source: String,
    val model: String,
    val blocks: List<String>,
    val parts: List<String>,
    val notices: List<String>,
) {
    val id: String = UUID.randomUUID().toString()

    companion object {
        @JvmStatic
        @Throws(Exception::class)
        fun translate(
            context: Context,
            source: String,
            model: String,
            cancel: java.util.concurrent.atomic.AtomicBoolean,
            progress: RetainedOperation.Progress,
        ): DocumentResult {
            val original = DocumentTranslation.paragraphs(source)
            val engine = DocumentEngine(context, model)
            val cache =
                engine.translate(
                    original,
                    cancel,
                ) { done, total -> progress.update("Перевод: $done / $total") }
            val blocks = ArrayList<String>()
            val parts = ArrayList<String>()
            val notices = ArrayList<String>()
            for (paragraph in original) {
                if (cancel.get() || Thread.currentThread().isInterrupted)
                    throw InterruptedException()
                val block = cache[CompletenessRules.block(paragraph)]!!
                val translated = ArrayList<String>()
                val units = CompletenessRules.units(paragraph)
                for (unit in units) translated.add(cache[unit]!!)
                blocks.add(block)
                parts.add(translated.joinToString("\n"))
                notices.add(
                    if (
                        units.any(engine.incompleteUnits::contains) ||
                            engine.incompleteUnits.contains(CompletenessRules.block(paragraph))
                    )
                        "Не все фрагменты переведены. Оригинал отмечен в результате."
                    else CompletenessRules.warning(block, translated, units)
                )
            }
            return DocumentResult(source, engine.model, blocks, parts, notices)
        }
    }
}
