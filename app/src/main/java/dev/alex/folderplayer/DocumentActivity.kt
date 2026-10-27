package dev.alex.folderplayer

import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.ArrayList
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class DocumentActivity : ThemedActivity() {
    private var operation: RetainedOperation<DocumentResult> = RetainedOperation()
    private var appliedOperationId: String? = null
    private var reader: ScrollView? = null
    private var source: String = ""
    private var initialSource: String = ""
    private var track: String? = null
    private var model: String = ""
    private var original: List<String> = ArrayList()
    private var blocks: List<String> = ArrayList()
    private var parts: List<String> = ArrayList()
    private var notices: List<String> = ArrayList()
    private var useBlock: BooleanArray = BooleanArray(0)
    private var busy = false
    private var complete = false
    private lateinit var status: TextView
    private lateinit var output: TextView
    private lateinit var run: Button
    private lateinit var stop: Button
    private lateinit var review: Button
    private lateinit var all: Button
    private lateinit var save: Button
    private lateinit var copy: Button

    private fun label(s: String): TextView {
        val v = TextView(this)
        v.setTextColor(AppAppearance.textColor)
        v.textSize = 17f
        v.text = s
        v.setPadding(0, 12, 0, 12)
        v.setTextIsSelectable(true)
        return v
    }

    private fun button(s: String, l: View.OnClickListener): Button {
        val b = Button(this)
        b.text = s
        b.setOnClickListener(l)
        return b
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        operation =
            (lastCustomNonConfigurationInstance as? RetainedOperation<DocumentResult>)
                ?: RetainedOperation()
        source = intent.getStringExtra("source") ?: ""
        initialSource = source
        track = intent.getStringExtra("track")
        model = intent.getStringExtra("translationModel") ?: ""
        if (model.isEmpty() || !TranslationClient.MODELS.containsKey(model))
            model = TranslationClient(this).modelId
        original = DocumentTranslation.paragraphs(source)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setBackgroundColor(AppAppearance.palette.background)
        box.setPadding(24, 24, 24, 24)
        if (Build.VERSION.SDK_INT >= 30)
            box.setOnApplyWindowInsetsListener { v, i ->
                val b = i.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(24, b.top + 16, 24, b.bottom + 16)
                i
            }
        val sc = ScrollView(this)
        reader = sc
        sc.addView(box)
        setContentView(sc)
        box.addView(label("Перевод"))
        status =
            label(
                "Перевод выполняется на устройстве. Язык: " + TranslationClient.MODELS[model]
            )
        box.addView(status)
        run = button("Перевести") { confirmTranslation() }
        stop =
            button("Отмена") {
                operation.cancel()
                status.text = "Перевод отменён. Можно повторить."
                active(false)
            }
        review = button("Места с возможным пропуском") { list(true) }
        all = button("Все фрагменты") { list(false) }
        save = button("Сохранить для песни") { saveResult() }
        if (track == null) save.visibility = View.GONE
        box.addView(run)
        box.addView(stop)
        box.addView(save)
        copy = button("Копировать текущий результат") { copyToClipboard(result()) }
        box.addView(button("Дополнительно") { extras() })
        output = label("")
        box.addView(output)
        box.addView(button("Назад") { finish() })
        active(false)
        ReadingPosition.attach(this, sc, "document:$initialSource\n$model")
        if (state != null) {
            source = state.getString("documentSource", source)
            model = state.getString("documentModel", model) ?: model
            original = DocumentTranslation.paragraphs(source)
            val completed = state.getString("completed")
            if (completed != null) {
                try {
                    restore(JSONObject(completed))
                } catch (ignored: Exception) {
                    status.text = "Не удалось восстановить результат"
                }
            }
        }
        if (state != null) appliedOperationId = state.getString("appliedOperationId")
        operation.attach { showOperation(it) }
        if (
            state == null &&
                operation.state().key == null &&
                intent.getBooleanExtra("translateNow", false)
        )
            translate()
    }

    private fun active(b: Boolean) {
        busy = b
        run.isEnabled = !b
        run.text = if (complete) "Перевести заново" else "Перевести"
        stop.isEnabled = b
        stop.visibility = if (b) View.VISIBLE else View.GONE
        for (x in arrayOf(review, all, copy)) x.isEnabled = !b && complete
        save.isEnabled = !b && complete && track != null
    }

    private fun confirmTranslation() {
        if (!complete) {
            translate()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Перевести заново?")
            .setMessage("Текущий результат будет заменён после завершения.")
            .setPositiveButton("Перевести") { _, _ -> translate() }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun extras() {
        val items = ArrayList<String>()
        if (complete) {
            items.add("Копировать перевод")
            items.add("Сравнить фрагменты")
            items.add("Проверить возможные пропуски")
        }
        items.add("Как работает перевод")
        AlertDialog.Builder(this)
            .setTitle("Дополнительно")
            .setItems(items.toTypedArray()) { _, n ->
                when (items[n]) {
                    "Копировать перевод" -> copyToClipboard(result())
                    "Сравнить фрагменты" -> list(false)
                    "Проверить возможные пропуски" -> list(true)
                    else ->
                        AlertDialog.Builder(this)
                            .setMessage(
                                "Каждый абзац переводится по предложениям и целиком. Можно сравнить" +
                                    " варианты и выбрать подходящий. Предупреждения о пропусках не" +
                                    " проверяют весь смысл. Перевод не синхронизирован со временем" +
                                    " LRC."
                            )
                            .setPositiveButton(getString(R.string.close), null)
                            .show()
                }
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun translate() {
        if (busy) return
        if (source.trim().isEmpty() || source.length > 20000 || source.split("\n").size > 500) {
            status.text = "Нужен непустой текст до 20 000 символов и 500 строк"
            return
        }
        val input = source
        val language = model
        val context = applicationContext
        active(true)
        operation.start(input + "\n" + language) { cancel, progress ->
            DocumentResult.translate(context, input, language, cancel, progress)
        }
    }

    private fun showOperation(state: RetainedOperation.Snapshot<DocumentResult>) {
        if (state.key == null) return
        if (state.busy) {
            active(true)
            status.text = state.message
            return
        }
        if (state.value != null && state.value.id != appliedOperationId) {
            val value = state.value
            appliedOperationId = value.id
            source = value.source
            model = value.model
            original = DocumentTranslation.paragraphs(source)
            blocks = value.blocks
            parts = value.parts
            notices = value.notices
            useBlock = BooleanArray(blocks.size)
            complete = true
            refresh()
        }
        if (state.failure != null)
            status.text = "Перевод не завершён: " + state.failure.message
        active(false)
    }

    private fun result(): String {
        val selected = ArrayList<String>()
        for (i in parts.indices) selected.add(if (useBlock[i]) blocks[i] else parts[i])
        return DocumentTranslation.assemble(selected)
    }

    private fun refresh() {
        output.text = result()
        var n = 0
        for (s in notices) if (s.isNotEmpty()) n++
        status.text = "Готово. Мест с возможным пропуском: $n. Перевод может содержать ошибки."
    }

    private fun list(suspicious: Boolean) {
        if (busy || !complete) return
        val ids = ArrayList<Int>()
        val labels = ArrayList<String>()
        for (i in original.indices) {
            if (original[i].trim().isNotEmpty() && (!suspicious || notices[i].isNotEmpty())) {
                ids.add(i)
                labels.add("Фрагмент ${i + 1}${if (useBlock[i]) " · блок" else " · предложения"}")
            }
        }
        if (ids.isEmpty()) {
            status.text =
                "Признаки крупного пропуска не найдены. Смысл и детали могут содержать ошибки."
            return
        }
        AlertDialog.Builder(this)
            .setTitle(if (suspicious) "Возможные пропуски" else "Все фрагменты")
            .setItems(labels.toTypedArray()) { _, n -> choose(ids[n]) }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun choose(i: Int) {
        val sc = ScrollView(this)
        sc.addView(
            label(
                "Оригинал\n" +
                    original[i] +
                    "\n\nБлок\n" +
                    blocks[i] +
                    "\n\nПредложения\n" +
                    parts[i] +
                    "\n\n" +
                    notices[i]
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Фрагмент ${i + 1}")
            .setView(sc)
            .setPositiveButton(
                "Выбрать предложения"
            ) { _, _ ->
                useBlock[i] = false
                refresh()
            }
            .setNeutralButton(
                "Выбрать блок"
            ) { _, _ ->
                useBlock[i] = true
                refresh()
            }
            .setNegativeButton(getString(R.string.close), null)
            .show()
    }

    private fun copyToClipboard(value: String) {
        (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
            .setPrimaryClip(android.content.ClipData.newPlainText("AFP перевод", value))
        Toast.makeText(this, "Перевод скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun saveResult() {
        if (busy || !complete || track == null) return
        if (!source.contentEquals(initialSource) || result().contains("⟦Не переведено⟧")) {
            status.text = "Неполный результат можно скопировать через «Дополнительно»."
            return
        }
        try {
            TranslationStore(this).save(track!!, source, result(), model)
            status.text = "Перевод сохранён для песни."
        } catch (e: Exception) {
            status.text = "Не удалось сохранить: " + e.message
        }
    }

    private fun restore(row: JSONObject) {
        try {
            source = row.getString("source")
            model = row.getString("model")
            original = DocumentTranslation.paragraphs(source)
            blocks = strings(row.getJSONArray("blocks"))
            parts = strings(row.getJSONArray("sentences"))
            notices = strings(row.getJSONArray("warnings"))
            useBlock = BooleanArray(blocks.size)
            val choices = row.getJSONArray("choices")
            for (i in useBlock.indices) useBlock[i] = choices.getString(i) == "block"
            if (original.size != blocks.size) throw JSONException("Части не совпадают")
            complete = true
            appliedOperationId = operation.state().value?.id
            refresh()
            active(false)
        } catch (e: Exception) {
            complete = false
            active(false)
            status.text = "Не удалось открыть сохранение: " + e.message
        }
    }

    @Throws(JSONException::class)
    private fun strings(a: JSONArray): List<String> {
        val out = ArrayList<String>()
        for (i in 0 until a.length()) out.add(a.getString(i))
        return out
    }

    override fun onRetainCustomNonConfigurationInstance(): Any = operation

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("appliedOperationId", appliedOperationId)
        out.putString("documentSource", source)
        out.putString("documentModel", model)
        if (complete) {
            try {
                val row =
                    JSONObject()
                        .put("source", source)
                        .put("model", model)
                        .put("blocks", JSONArray(blocks))
                        .put("sentences", JSONArray(parts))
                        .put("warnings", JSONArray(notices))
                val choices = JSONArray()
                for (b in useBlock) choices.put(if (b) "block" else "sentences")
                row.put("choices", choices)
                out.putString("completed", row.toString())
            } catch (ignored: Exception) {}
        }
        super.onSaveInstanceState(out)
    }

    override fun onDestroy() {
        operation.detach()
        if (!isChangingConfigurations) operation.close()
        super.onDestroy()
    }
}
