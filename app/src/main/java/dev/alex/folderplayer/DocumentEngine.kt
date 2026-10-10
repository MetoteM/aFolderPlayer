package dev.alex.folderplayer

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/** One engine request batches unique blocks and sentence units, retaining repeats on assembly. */
internal class DocumentEngine(private val context: Context) {
 val model = TranslationClient(context).modelId
 fun interface Progress { fun update(done: Int, total: Int) }
 fun translate(paragraphs: List<String>, cancel: AtomicBoolean, progress: Progress): Map<String, String> {
  check(context.getSharedPreferences("extensions", 0).getBoolean("enabled", false)) { "Включи перевод в расширениях плеера" }
  val units = linkedSetOf<String>()
  for (paragraph in paragraphs) {
   units.add(CompletenessRules.block(paragraph))
   units.addAll(CompletenessRules.units(paragraph))
  }
  units.remove("")
  val source = units.joinToString("\n")
  require(source.any { it.isLetter() }) { "В оригинале нет слов для перевода" }
  require(!model.startsWith("opus-en-ru-") || source.count { it in 'А'..'я' || it=='ё' || it=='Ё' } <= source.count { it in 'A'..'Z' || it in 'a'..'z' }) { "Этот пакет переводит с английского на русский" }
  require(source.length <= 20000 && units.size <= 500) { "Сравнение слишком большого текста: выбери более короткий оригинал" }
  val client = TranslationClient(context)
  val translated = client.translate(source, cancel, model) { done, total -> progress.update(done, total) }.split("\n")
  if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
  check(translated.size == units.size && translated.all { it.isNotBlank() }) { "Движок вернул неполный результат" }
  return units.zip(translated).toMap() + ("" to "")
 }
}
