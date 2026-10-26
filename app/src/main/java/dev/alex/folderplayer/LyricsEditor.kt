package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.widget.*
import androidx.media3.common.Player
import androidx.media3.session.MediaController

/** Screen-owned text editing, timing and preview dialogs; never retained by ViewModel work. */
internal class LyricsEditor(
    private val host: Activity,
    private val currentController: () -> MediaController?,
    private val hiddenTracks: () -> Set<String>,
    private val openCurrent: (Track) -> Boolean,
    private val importText: (Track) -> Unit,
    private val exportText: (Track, Boolean) -> Unit,
    private val lyricsSearch: LyricsSearch,
    private val background: ActivityWork,
    private val networkWork: ActivityWork,
) : AutoCloseable {
    private val prefs = host.getSharedPreferences("playback", 0)
    private val controller
        get() = currentController()

    private val ui = PlayerUi(host)
    private var alive = true
    private var lyricUpdate: (() -> Unit)? = null
    private var networkCancel: LyricsSearch.Cancel? = null
    private val dialogs =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap<Dialog, Boolean>())
    val ticking
        get() = lyricUpdate != null

    fun tick() {
        lyricUpdate?.invoke()
    }

    override fun close() {
        alive = false
        lyricUpdate = null
        networkCancel?.cancel()
        networkWork.cancelAll()
        dialogs.toList().forEach { it.dismiss() }
        dialogs.clear()
    }

    private fun row() = ui.row()

    private fun weight() = ui.weight()

    private fun label(text: String, size: Float = 16f) = ui.label(text, size)

    private fun button(text: String, action: () -> Unit) = ui.button(text, action)

    private fun toast(text: String) = ui.toast(text)

    private fun clock(ms: Long) = ui.clock(ms)

    fun localMedia(track: Track) {
        if (!openCurrent(track)) manageMedia(track)
    }

    fun manageMedia(track: Track) {
        val panel =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        val actions = row()
        panel.addView(actions)
        val edit = button("Свой текст") {}
        val mark = button("Разметить") {}
        val export = button("Экспорт") {}
        actions.addView(edit, weight())
        actions.addView(mark, weight())
        actions.addView(export, weight())
        mark.isEnabled = false
        export.isEnabled = false
        val online = button("Найти текст в интернете") {}
        panel.addView(online)
        val status = label("Читаем локальные данные…", 14f)
        panel.addView(status)
        val text = label("", 18f)
        text.setTextIsSelectable(true)
        panel.addView(text)
        val scroll = ScrollView(host).apply { addView(panel) }
        var raw = ""
        val dialog =
            AlertDialog.Builder(host)
                .setTitle(track.title)
                .setView(scroll)
                .setNeutralButton("Импорт TXT / LRC") { _, _ -> importText(track) }
                .setPositiveButton("Закрыть", null)
                .create()
                .also { dialogs.add(it) }
        online.setOnClickListener {
            dialog.dismiss()
            dialogSearch(track)
        }
        edit.setOnClickListener {
            dialog.dismiss()
            editLyrics(track, raw)
        }
        mark.setOnClickListener {
            dialog.dismiss()
            markLyrics(track, raw)
        }
        export.setOnClickListener { exportText(track, LyricsDocument(raw).cues.isNotEmpty()) }
        dialog.setOnDismissListener { lyricUpdate = null }
        dialog.show()
        background.submit({ ctx ->
            var picture: android.graphics.Bitmap? = null
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(ctx, Uri.parse(track.sourceUri))
                    retriever.embeddedPicture?.let { bytes ->
                        val bounds =
                            android.graphics.BitmapFactory.Options().apply {
                                inJustDecodeBounds = true
                            }
                        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                        val opts =
                            android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = 1
                                while (
                                    bounds.outWidth / inSampleSize > 1024 ||
                                        bounds.outHeight / inSampleSize > 1024
                                ) inSampleSize *= 2
                            }
                        picture =
                            android.graphics.BitmapFactory.decodeByteArray(
                                bytes,
                                0,
                                bytes.size,
                                opts,
                            )
                    }
                } finally {
                    retriever.release()
                }
            }
            val textResult = runCatching { SongContent(ctx).load(track) }

            picture to textResult
        }) { result ->
            val (picture, textResult) =
                result.getOrElse {
                    return@submit
                }

            if (alive && dialog.isShowing) {
                picture?.let { bitmap ->
                    panel.addView(
                        ImageView(host).apply {
                            setImageBitmap(bitmap)
                            adjustViewBounds = true
                            maxHeight = 600
                            contentDescription = "Обложка"
                        },
                        1,
                    )
                }
                prefs.getString("lyricsSource:${track.uri}", null)?.let {
                    panel.addView(label(it, 12f), 2)
                }
                raw = textResult.getOrNull().orEmpty()
                val document = LyricsDocument(raw)
                mark.isEnabled = raw.isNotBlank()
                export.isEnabled = raw.isNotBlank()
                if (raw.isBlank()) {
                    status.text =
                        if (textResult.isFailure)
                            "Не удалось прочитать локальный текст. Можно вставить свой."
                        else "Текста пока нет. Вставь слова или импортируй TXT / LRC."
                } else if (document.cues.isEmpty()) {
                    status.text = "Сохранённый текст · без разметки времени"
                    text.text = raw
                } else {
                    val offsets = mutableListOf<Int>()
                    var count = 0
                    document.cues.forEach {
                        offsets.add(count)
                        count += it.text.length + 1
                    }
                    val display = document.plain + "\n"
                    text.text = display
                    var active = Int.MIN_VALUE
                    lyricUpdate = {
                        val matching = controller?.currentMediaItem?.mediaId == track.uri
                        val index =
                            if (matching) document.activeIndex(controller?.currentPosition ?: 0)
                            else -1
                        status.text =
                            if (matching) "LRC · строка выделяется по времени песни"
                            else "Для синхронизации включи эту песню"
                        if (index != active) {
                            active = index
                            val spans = android.text.SpannableString(display)
                            if (index >= 0) {
                                val start = document.groupStart(index)
                                for (i in start..index) {
                                    if (document.cues[i].text.isNotEmpty()) {
                                        spans.setSpan(
                                            android.text.style.ForegroundColorSpan(
                                                PlayerScreens.accent
                                            ),
                                            offsets[i],
                                            offsets[i] + document.cues[i].text.length,
                                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                        )
                                        spans.setSpan(
                                            android.text.style.StyleSpan(Typeface.BOLD),
                                            offsets[i],
                                            offsets[i] + document.cues[i].text.length,
                                            android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                        )
                                    }
                                }
                                text.text = spans
                                text.post {
                                    if (dialog.isShowing)
                                        text.layout?.let { l ->
                                            scroll.smoothScrollTo(
                                                0,
                                                text.top +
                                                    l.getLineTop(
                                                        l.getLineForOffset(offsets[start])
                                                    ) - scroll.height / 3,
                                            )
                                        }
                                }
                            } else text.text = spans
                        }
                    }
                    lyricUpdate?.invoke()
                }
            }
        }
    }

    fun editLyrics(track: Track, initial: String) {
        val input =
            EditText(host).apply {
                setText(initial)
                gravity = Gravity.TOP
                minLines = 8
                maxLines = 16
                hint = "Вставь слова песни или LRC с метками [00:12.340]"
                filters = arrayOf(android.text.InputFilter.LengthFilter(512 * 1024))
            }
        val dialog =
            AlertDialog.Builder(host)
                .setTitle("Текст: ${track.title}")
                .setView(input)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create()
                .also { dialogs.add(it) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString()
                if (value.isBlank()) {
                    input.error = "Вставь текст песни"
                    return@setOnClickListener
                }
                runCatching { LyricsDocument(value) }
                    .onFailure { input.error = it.message }
                    .onSuccess {
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                        background.submit({ ctx ->
                            LyricsStore(ctx).save(track.uri, value)
                            ctx.getSharedPreferences("playback", 0)
                                .edit()
                                .remove("lyricsSource:${track.uri}")
                                .apply()
                        }) { result ->
                            if (alive) {
                                if (result.isSuccess) {
                                    dialog.dismiss()
                                    localMedia(track)
                                } else {
                                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                                    input.error = "Не удалось сохранить текст"
                                }
                            }
                        }
                    }
            }
        }
        dialog.show()
    }

    fun markLyrics(track: Track, raw: String) {
        val lines = LyricsDocument(raw).plain.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return toast("Сначала добавь слова песни")
        val times = mutableListOf<Long>()
        val panel =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        panel.addView(
            label(
                "Включи песню с начала. На начале каждой строки нажимай «Отметить». Для пауз добавь пустую строку с меткой в редакторе LRC.",
                14f,
            )
        )
        val caption = label("", 18f)
        panel.addView(caption)
        val controls = row()
        panel.addView(controls)
        controls.addView(
            button("С начала") {
                if (track.uri in hiddenTracks()) {
                    toast("Трек скрыт от воспроизведения. Сначала верни его кнопкой глаза")
                    return@button
                }
                controller?.let { c ->
                    if (c.currentMediaItem?.mediaId != track.uri) {
                        var index =
                            (0 until c.mediaItemCount).firstOrNull {
                                c.getMediaItemAt(it).mediaId == track.uri
                            }
                        if (index == null) {
                            c.addMediaItem(track.item())
                            index = c.mediaItemCount - 1
                        }
                        c.seekTo(index, 0)
                    } else c.seekTo(0)
                    c.prepare()
                    c.play()
                    times.clear()
                    caption.text = "1 / ${lines.size}\n${lines[0]}"
                }
            },
            weight(),
        )
        controls.addView(
            button("Пауза / ▶") {
                controller?.let {
                    if (it.isPlaying) it.pause()
                    else {
                        if (it.playbackState == Player.STATE_IDLE) it.prepare()
                        it.play()
                    }
                }
            },
            weight(),
        )
        val next = button("Отметить строку") {}
        panel.addView(next)
        panel.addView(
            button("Отменить последнюю метку") {
                if (times.isNotEmpty()) times.removeAt(times.lastIndex)
                caption.text = "${times.size+1} / ${lines.size}\n${lines[times.size]}"
                next.isEnabled = true
            }
        )
        val dialog =
            AlertDialog.Builder(host)
                .setTitle("Разметка: ${track.title}")
                .setView(panel)
                .setPositiveButton("Сохранить LRC", null)
                .setNegativeButton("Отмена", null)
                .create()
                .also { dialogs.add(it) }
        caption.text = "1 / ${lines.size}\n${lines[0]}"
        next.setOnClickListener {
            val c = controller
            if (c == null || c.currentMediaItem?.mediaId != track.uri || !c.isPlaying)
                toast("Сначала включи эту песню")
            else if (times.isNotEmpty() && c.currentPosition < times.last())
                toast("После перемотки назад отмени предыдущие метки")
            else {
                times.add(c.currentPosition)
                next.isEnabled = times.size < lines.size
                caption.text =
                    if (times.size == lines.size) "Все строки отмечены. Сохрани LRC."
                    else "${times.size+1} / ${lines.size}\n${lines[times.size]}"
            }
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (times.size != lines.size) toast("Отметь все строки перед сохранением")
                else {
                    val value =
                        lines.indices.joinToString("\n") {
                            LyricsDocument.timestamp(times[it]) + lines[it]
                        }
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                    background.submit({ ctx ->
                        LyricsStore(ctx).save(track.uri, value)
                        ctx.getSharedPreferences("playback", 0)
                            .edit()
                            .remove("lyricsSource:${track.uri}")
                            .apply()
                    }) { result ->
                        if (alive) {
                            if (result.isSuccess) {
                                dialog.dismiss()
                                localMedia(track)
                            } else {
                                toast("Не удалось сохранить LRC")
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            }
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun dialogSearch(track: Track) {
        val panel =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        val title =
            EditText(host).apply {
                hint = "Название песни"
                setText(track.title)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(title)
        val artist =
            EditText(host).apply {
                hint = "Исполнитель (можно оставить пустым)"
                setText(track.artist)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(artist)
        panel.addView(
            label(
                "Поиск по названию и исполнителю. Перед сохранением проверь выбранный текст.",
                14f,
            )
        )
        val status = label("", 14f)
        panel.addView(status)
        var busy = false
        val dialog =
            AlertDialog.Builder(host)
                .setTitle("Поиск текста")
                .setView(ScrollView(host).apply { addView(panel) })
                .setPositiveButton("Поиск", null)
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("В интернете", null)
                .create()
                .also { dialogs.add(it) }
        dialog.setOnDismissListener {
            networkCancel?.cancel()
            networkWork.cancelAll()
            networkCancel = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                LyricsWeb.open(host, title.text.toString(), artist.text.toString())
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (busy) return@setOnClickListener
                val name = title.text.toString().trim()
                val performer = artist.text.toString().trim()
                if (name.isBlank()) {
                    title.error = "Введи название песни"
                    return@setOnClickListener
                }
                busy = true
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                status.text = "Ищем текст…"
                val cancel = LyricsSearch.Cancel()
                networkCancel = cancel
                val search = lyricsSearch
                networkWork.submit({ ctx ->
                    try {
                        search.searchExpanded(name, performer, track.duration, cancel)
                    } finally {
                        ctx.getSharedPreferences("playback", 0)
                            .edit()
                            .putLong("lyricsRetryUntil", search.cooldown())
                            .apply()
                    }
                }) { result ->
                    if (alive && dialog.isShowing) {
                        busy = false
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        result
                            .onFailure { status.text = SearchSupport.message(it, "LRCLIB") }
                            .onSuccess { found ->
                                if (found.isEmpty())
                                    status.text =
                                        "Текст не найден. Попробуй убрать исполнителя или поправить название. Для своей песни можно вставить исходные слова."
                                else {
                                    dialog.dismiss()
                                    lyricsResults(track, found)
                                }
                            }
                    }
                }
            }
        }
        dialog.show()
    }

    private fun lyricsResults(track: Track, found: List<LyricsSearch.Result>) {
        val labels =
            found.map { item ->
                val duration =
                    if (item.duration.isFinite() && item.duration > 0)
                        clock((item.duration * 1000).toLong())
                    else "длительность неизвестна"
                "${item.title} — ${item.artist}\n${item.album} · $duration · " +
                    if (item.instrumental && item.text.isBlank()) "без вокала"
                    else if (item.timed) "LRC" else "текст"
            }
        AlertDialog.Builder(host)
            .setTitle("Выбери запись · ${clock(track.duration)} на телефоне")
            .setItems(labels.toTypedArray()) { _, i -> previewLyrics(track, found[i]) }
            .setNeutralButton("Другой запрос") { _, _ -> dialogSearch(track) }
            .setNegativeButton("Закрыть", null)
            .show()
            .also { dialogs.add(it) }
    }

    private fun previewLyrics(track: Track, item: LyricsSearch.Result) {
        val panel =
            LinearLayout(host).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        panel.addView(
            label(
                "${item.title} — ${item.artist}\n${item.album}\nТекст сохранится для «${track.title}» и заменит прежний сохранённый текст.",
                14f,
            )
        )
        val preview =
            label(
                if (item.text.isBlank()) "Запись помечена как инструментальная: текста нет."
                else LyricsDocument(item.text).plain,
                16f,
            )
        preview.setTextIsSelectable(true)
        panel.addView(preview)
        val dialog =
            AlertDialog.Builder(host)
                .setTitle("Проверить текст")
                .setView(ScrollView(host).apply { addView(panel) })
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create()
                .also { dialogs.add(it) }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = item.text.isNotBlank()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                background.submit({ ctx ->
                    LyricsStore(ctx).save(track.uri, item.text)
                    ctx.getSharedPreferences("playback", 0)
                        .edit()
                        .putString(
                            "lyricsSource:${track.uri}",
                            "Источник: LRCLIB · ${item.title} — ${item.artist}",
                        )
                        .apply()
                }) { result ->
                    if (alive) {
                        if (result.isSuccess) {
                            dialog.dismiss()
                            localMedia(track)
                        } else {
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                            toast("Не удалось сохранить текст")
                        }
                    }
                }
            }
        }
        dialog.show()
    }
}
