package dev.alex.folderplayer

import android.app.Service
import android.content.Intent
import android.os.ParcelFileDescriptor
import dev.alex.afptranslation.ITranslationCallback
import dev.alex.afptranslation.ITranslationService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Bound local component. No INTERNET permission, and no automatic startup. */
class TranslationService : Service() {
    private val worker =
        Executors.newSingleThreadExecutor { task ->
            Thread(task, "AFP-translate").apply { priority = Thread.MIN_PRIORITY }
        }
    private val jobs = ConcurrentHashMap<String, AtomicBoolean>()
    private val store by lazy { TranslationStore(this) }
    private val binder =
        object : ITranslationService.Stub() {
            override fun apiVersion() = 1

            override fun recoveryVersion() = 1

            override fun modelReady(id: String) = TranslationStore.supported(id) && store.ready(id)

            override fun modelBytes(id: String) =
                if (TranslationStore.supported(id)) store.bytes(id) else 0

            override fun installModel(
                id: String,
                file: ParcelFileDescriptor,
                callback: ITranslationCallback,
            ) {
                require(id.length <= 128)
                val owned = ParcelFileDescriptor.dup(file.fileDescriptor)
                file.close()
                worker.execute {
                    try {
                        val installed =
                            ParcelFileDescriptor.AutoCloseInputStream(owned).use {
                                store.install(it)
                            }
                        callback.complete(id, installed)
                    } catch (e: Exception) {
                        runCatching {
                            callback.failed(id, e.message ?: "Не удалось установить пакет")
                        }
                    }
                }
            }

            override fun translate(
                id: String,
                model: String,
                text: String,
                callback: ITranslationCallback,
            ) {
                startTranslation(id, model, text, callback, false)
            }

            override fun translateRecovering(
                id: String,
                model: String,
                text: String,
                callback: ITranslationCallback,
            ) {
                startTranslation(id, model, text, callback, true)
            }

            private fun startTranslation(
                id: String,
                model: String,
                text: String,
                callback: ITranslationCallback,
                partial: Boolean,
            ) {
                require(
                    id.length <= 128 && TranslationStore.supported(model) && text.length <= 20000
                )
                val cancel = AtomicBoolean(false)
                jobs.put(id, cancel)?.set(true)
                worker.execute {
                    try {
                        if (cancel.get() || Thread.currentThread().isInterrupted) return@execute
                        check(store.ready(model)) { "Языковой пакет не установлен" }
                        var result = text
                        val failed = sortedSetOf<Int>()
                        val original = text.replace("\r", "").split("\n")
                        val stages = ModelPackages.find(model)!!.stages
                        for ((index, stage) in stages.withIndex()) {
                            check(!cancel.get()) { "Отменено" }
                            result =
                                OfflineTranslator(
                                        java.io.File(store.model(model), stage.directory),
                                        stage.padding,
                                    )
                                    .use { engine ->
                                        val input =
                                            result
                                                .split("\n")
                                                .mapIndexed { line, value ->
                                                    if (line in failed) "" else value
                                                }
                                                .joinToString("\n")
                                        val outcome =
                                            engine.translateResult(
                                                input,
                                                { cancel.get() },
                                                { done, total ->
                                                    if (!cancel.get())
                                                        runCatching {
                                                            callback.progress(
                                                                id,
                                                                index * total + done,
                                                                stages.size * total,
                                                            )
                                                        }
                                                },
                                                partial,
                                            )
                                        failed.addAll(outcome.failedLines)
                                        outcome.text
                                    }
                        }
                        check(result.length <= 200000) { "Перевод слишком большой" }
                        if (!cancel.get()) {
                            if (partial) {
                                val lines =
                                    result.split("\n").mapIndexed { line, value ->
                                        if (line in failed) original[line] else value
                                    }
                                val payload =
                                    org.json
                                        .JSONObject()
                                        .put("text", lines.joinToString("\n"))
                                        .put("failedLines", org.json.JSONArray(failed.toList()))
                                callback.complete(id, payload.toString())
                            } else callback.complete(id, result)
                        }
                    } catch (e: Throwable) {
                        if (!cancel.get())
                            runCatching {
                                callback.failed(
                                    id,
                                    if (e is OutOfMemoryError) "Недостаточно памяти для перевода"
                                    else e.message ?: "Не удалось перевести",
                                )
                            }
                    } finally {
                        jobs.remove(id, cancel)
                    }
                }
            }

            override fun cancel(id: String) {
                jobs[id]?.set(true)
            }

            override fun removeModel(id: String) {
                require(TranslationStore.supported(id))
                jobs.values.forEach { it.set(true) }
                worker
                    .submit { store.removeModel(id) }
                    .get(30, java.util.concurrent.TimeUnit.SECONDS)
            }
        }

    override fun onBind(intent: Intent) = binder

    override fun onDestroy() {
        jobs.values.forEach { it.set(true) }
        worker.shutdownNow()
        super.onDestroy()
    }
}
