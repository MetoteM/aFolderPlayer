package dev.alex.folderplayer

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

internal data class SongTranslationResult(
    val source: String,
    val text: String,
    val model: String,
    val incomplete: Int = 0,
)

internal class ChooseSongLanguage(val source: String, val models: List<Pair<String, String>>) :
    Exception()

/** One language/cache policy; preserve old translations until replacement succeeds. */
internal class SongTranslationRepository(context: Context) {
    private val context = context.applicationContext

    fun translate(
        track: Track,
        renew: Boolean,
        cancel: AtomicBoolean,
        progress: RetainedOperation.Progress,
    ): SongTranslationResult {
        check(context.getSharedPreferences("extensions", 0).getBoolean("enabled", false)) {
            "Перевод выключен. Его можно включить в расширениях."
        }
        val raw =
            SongContent(context).load(track) ?: error("Сначала найди или добавь оригинальный текст")
        val source = LyricsDocument(raw).plain
        require(source.any { it.isLetter() }) { "В оригинале нет слов для перевода" }
        val manual = SongTranslationChoice(context).read(track.uri, source)
        val expected = manual ?: SongLanguage.detect(source)
        if (expected == SongLanguage.RUSSIAN)
            return SongTranslationResult(source, source, SongLanguage.RUSSIAN)
        val store = TranslationStore(context)
        if (!renew) {
            for (model in SongTranslation.cachedModels(expected)) {
                val checkpoint = recovery(store, track.uri, source, model)
                if (checkpoint?.optBoolean("incomplete", false) == true) continue
                store.read(track.uri, source, model)?.let {
                    return SongTranslationResult(source, it, model)
                }
            }
        }
        val installed = TranslationClient(context).availableModels()
        val model =
            when (val choice = SongTranslation.decide(source, installed.keys, manual)) {
                is SongTranslation.Decision.Ready -> choice.model
                is SongTranslation.Decision.Missing ->
                    throw TranslationClient.MissingSongModel(choice.model)
                SongTranslation.Decision.Choose ->
                    throw ChooseSongLanguage(source, installed.toList())
                SongTranslation.Decision.Russian ->
                    return SongTranslationResult(source, source, SongLanguage.RUSSIAN)
            }
        if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        val engine = DocumentEngine(context, model)
        if (!renew)
            recovery(store, track.uri, source, model)?.let { data ->
                runCatching {
                    val units = data.getJSONObject("units")
                    engine.recoveredUnits =
                        units.keys().asSequence().associateWith { units.getString(it) }
                }
            }
        val text =
            engine.translateSentences(source, cancel) { done, total ->
                progress.update("Перевод: $done / $total")
            }
        if (cancel.get() || Thread.currentThread().isInterrupted) throw InterruptedException()
        store.save(
            track.uri,
            source,
            org.json
                .JSONObject()
                .put("units", org.json.JSONObject(engine.completedUnits as Map<*, *>))
                .put("incomplete", engine.incompleteUnits.isNotEmpty())
                .toString(),
            "$model.recovery",
        )
        if (engine.incompleteUnits.isEmpty()) store.save(track.uri, source, text, model)
        return SongTranslationResult(source, text, model, engine.incompleteUnits.size)
    }

    private fun recovery(
        store: TranslationStore,
        uri: String,
        source: String,
        model: String,
    ): org.json.JSONObject? =
        store.read(uri, source, "$model.recovery")?.let {
            runCatching { org.json.JSONObject(it) }.getOrNull()
        }
}
