package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.*

/** Lyrics editing/search UI: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.stopDirectLyrics(clear: Boolean = false) {
        val busy = lyricsWork.state().busy
        if (busy && !clear)
            directId?.let { screens.lyricsSearching(it, false, "Поиск отменён. Можно повторить.") }
        lyricsWork.cancel()
        if (clear || busy) {
            directId = null
            directResults = emptyList()
        }
    }

internal fun MainActivity.editSearchName(track: Track) {
        val old =
            SearchIdentity(this).read(track.uri)
                ?: SongNames.queries(track).firstOrNull()
                ?: SongQuery(track.title, track.artist)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val title =
            EditText(this).apply {
                hint = "Название песни"
                setText(old.title)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        val artist =
            EditText(this).apply {
                hint = "Исполнитель"
                setText(old.artist)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(title)
        panel.addView(artist)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Поисковое название")
                .setView(panel)
                .setPositiveButton("Искать", null)
                .setNegativeButton(afp(R.string.cancel), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (title.text.isBlank()) title.error = "Введи название"
                else {
                    runCatching {
                            SearchIdentity(this)
                                .save(
                                    track.uri,
                                    SongQuery(
                                        title.text.toString().trim(),
                                        artist.text.toString().trim(),
                                    ),
                                )
                        }
                        .onSuccess {
                            dialog.dismiss()
                            showPage("lyrics")
                            directLyrics(track)
                        }
                        .onFailure { toast("Не удалось сохранить запрос") }
                }
            }
        }
        dialog.show()
    }

internal fun MainActivity.directLyrics(track: Track, force: Boolean = true) {
        val identity = SearchIdentity(this).read(track.uri)
        val miss =
            "lyricsMiss:" +
                LyricsDocument.key(
                    track.uri +
                        "\n" +
                        track.duration +
                        "\n" +
                        SongNames.queries(track, identity).joinToString()
                )
        if (!force && System.currentTimeMillis() - prefs.getLong(miss, 0) in 0..600000) {
            screens.lyricsSearching(
                track.uri,
                false,
                "Недавно текст не был найден. Можно повторить поиск вручную.",
            )
            return
        }
        if (lyricsWork.state().key == track.uri && lyricsWork.state().busy) return
        directResults = emptyList()
        directId = track.uri
        val context = applicationContext
        val cancel = LyricsSearch.Cancel()
        val search = lyricsSearch
        lyricsWork.start(track.uri) { _, progress ->
            progress.update("Ищем слова песни…")
            try {
                TrackSearch(context, search).search(track, identity, cancel)
            } finally {
                context
                    .getSharedPreferences("playback", 0)
                    .edit()
                    .putLong("lyricsRetryUntil", search.cooldown())
                    .apply()
            }
        }
        lyricsWork.onCancel { cancel.cancel() }
        lyricsWork.attach { showLyricsSearch(it) }
    }

internal fun MainActivity.showLyricsSearch(state: RetainedOperation.Snapshot<List<LyricsSearch.Result>>) {
        val track = currentTrack() ?: return
        if (!alive || page != "lyrics" || state.key != track.uri) return
        directId = track.uri
        if (state.busy) {
            screens.lyricsSearching(track.uri, true, state.message)
            return
        }
        state.failure?.let {
            screens.lyricsSearching(
                track.uri,
                false,
                SearchSupport.message(it, "Сервис текста") +
                    ". Можно повторить поиск или добавить свой текст через ⋮.",
            )
            return
        }
        state.value?.let { found ->
            val matches = found.filter { it.text.isNotBlank() }
            directResults = matches
            val identity = SearchIdentity(this).read(track.uri)
            val miss =
                "lyricsMiss:" +
                    LyricsDocument.key(
                        track.uri +
                            "\n" +
                            track.duration +
                            "\n" +
                            SongNames.queries(track, identity).joinToString()
                    )
            if (matches.isEmpty()) prefs.edit().putLong(miss, System.currentTimeMillis()).apply()
            else prefs.edit().remove(miss).apply()
            if (matches.isEmpty())
                screens.lyricsSearching(
                    track.uri,
                    false,
                    "Текст не найден. Можно добавить свой текст или импортировать TXT / LRC через ⋮.",
                )
            else {
                val query = identity ?: SongQuery(track.title, track.artist)
                val chosen =
                    LyricsMatch.automatic(
                        matches,
                        query.title,
                        query.artist,
                        track.album,
                        track.duration,
                    )
                if (chosen != null) saveDirectLyrics(track, chosen)
                else screens.setLyricsResults(track.uri, matches)
            }
        }
    }

internal fun MainActivity.saveDirectLyrics(track: Track, item: LyricsSearch.Result) {
        screens.lyricsSearching(track.uri, true, "Сохраняем текст…")
        runAsync({
            val document = LyricsDocument(item.text)
            lyricStore.save(track.uri, item.text)
            SearchIdentity(this).save(track.uri, SongQuery(item.title, item.artist))
            prefs
                .edit()
                .putString(
                    "lyricsSource:${track.uri}",
                    "Источник: LRCLIB · ${item.title} — ${item.artist}",
                )
                .apply()
            document
        }) { result ->
            postUi {
                if (page == "lyrics" && currentTrack()?.uri == track.uri) {
                    result
                        .onSuccess {
                            screens.lyricsSearching(track.uri, false, "")
                            screens.setLyrics(track.uri, it, null)
                            mediaLoadedId = track.uri
                        }
                        .onFailure {
                            screens.lyricsSearching(
                                track.uri,
                                false,
                                "Не удалось сохранить текст. Повтори попытку.",
                            )
                        }
                }
            }
        }
    }

internal fun MainActivity.loadLyrics(track: Track): String? = SongContent(this).load(track)

internal fun MainActivity.localMedia(track: Track) {
        if (page != "library" && track.uri == controller?.currentMediaItem?.mediaId) {
            mediaLoadedId = null
            showPage("lyrics")
            return
        }
        manageMedia(track)
    }

internal fun MainActivity.manageMedia(track: Track) {
        val panel =
            LinearLayout(this).apply {
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
        val scroll = ScrollView(this).apply { addView(panel) }
        var raw = ""
        val dialog =
            AlertDialog.Builder(this)
                .setTitle(track.title)
                .setView(scroll)
                .setNeutralButton("Импорт TXT / LRC") { _, _ ->
                    lyricTarget = track.uri
                    lyricsImporter.launch(arrayOf("*/*"))
                }
                .setPositiveButton(afp(R.string.close), null)
                .create()
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
        export.setOnClickListener {
            dialog.dismiss()
            lyricTarget = track.uri
            val timed = LyricsDocument(raw).cues.isNotEmpty()
            lyricsExporter.launch(
                track.title.replace(Regex("[\\/:*?\"<>|]"), "_") +
                    if (timed) ".lrc" else ".txt",
            )
        }
        dialog.setOnDismissListener { lyricUpdate = null }
        dialog.show()
        executor.execute {
            var picture: android.graphics.Bitmap? = null
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(track.sourceUri))
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
            val result = runCatching { loadLyrics(track) }
            postUi {
                if (dialog.isShowing) {
                    picture?.let { bitmap ->
                        panel.addView(
                            ImageView(this).apply {
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
                    raw = result.getOrNull().orEmpty()
                    val document = LyricsDocument(raw)
                    mark.isEnabled = raw.isNotBlank()
                    export.isEnabled = raw.isNotBlank()
                    if (raw.isBlank()) {
                        status.text =
                            if (result.isFailure)
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
    }

internal fun MainActivity.editLyrics(track: Track, initial: String) {
        val input =
            EditText(this).apply {
                setText(initial)
                gravity = Gravity.TOP
                minLines = 8
                maxLines = 16
                hint = "Вставь слова песни или LRC с метками [00:12.340]"
                filters = arrayOf(android.text.InputFilter.LengthFilter(512 * 1024))
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Текст: ${track.title}")
                .setView(input)
                .setPositiveButton(afp(R.string.save), null)
                .setNegativeButton(afp(R.string.cancel), null)
                .create()
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
                        runAsync({
                            lyricStore.save(track.uri, value)
                            prefs.edit().remove("lyricsSource:${track.uri}").apply()
                        }) { result ->
                            if (result.isSuccess) {
                                dialog.dismiss()
                                localMedia(track)
                            } else {
                                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                                    true
                                input.error = "Не удалось сохранить текст"
                            }
                        }
                    }
            }
        }
        dialog.show()
    }

internal fun MainActivity.markLyrics(track: Track, raw: String) {
        val lines = LyricsDocument(raw).plain.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return toast("Сначала добавь слова песни")
        val times = mutableListOf<Long>()
        val panel =
            LinearLayout(this).apply {
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
                if (track.uri in library.hiddenTracks()) {
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
            button("Пауза / ▶") { togglePlay() },
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
            AlertDialog.Builder(this)
                .setTitle("Разметка: ${track.title}")
                .setView(panel)
                .setPositiveButton("Сохранить LRC", null)
                .setNegativeButton(afp(R.string.cancel), null)
                .create()
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
                    runAsync({
                        lyricStore.save(track.uri, value)
                        prefs.edit().remove("lyricsSource:${track.uri}").apply()
                    }) { result ->
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
        dialog.show()
    }

internal fun MainActivity.dialogSearch(track: Track) {
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 8, 16, 8)
            }
        val title =
            EditText(this).apply {
                hint = "Название песни"
                setText(track.title)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(title)
        val artist =
            EditText(this).apply {
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
            AlertDialog.Builder(this)
                .setTitle("Поиск текста")
                .setView(ScrollView(this).apply { addView(panel) })
                .setPositiveButton("Поиск", null)
                .setNegativeButton(afp(R.string.close), null)
                .setNeutralButton("В интернете", null)
                .create()
        dialog.setOnDismissListener {
            networkCancel?.cancel()
            networkTask?.cancel(true)
            networkCancel = null
            networkTask = null
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                LyricsWeb.open(this, title.text.toString(), artist.text.toString())
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
                networkTask =
                    networkExecutor.submit {
                        val result = runCatching {
                            lyricsSearch.searchExpanded(name, performer, track.duration, cancel)
                        }
                        prefs.edit().putLong("lyricsRetryUntil", lyricsSearch.cooldown()).apply()
                        handler.post {
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
        }
        dialog.show()
    }

internal fun MainActivity.lyricsResults(track: Track, found: List<LyricsSearch.Result>) {
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
        AlertDialog.Builder(this)
            .setTitle("Выбери запись · ${clock(track.duration)} на телефоне")
            .setItems(labels.toTypedArray()) { _, i -> previewLyrics(track, found[i]) }
            .setNeutralButton("Другой запрос") { _, _ -> dialogSearch(track) }
            .setNegativeButton(afp(R.string.close), null)
            .show()
    }

internal fun MainActivity.previewLyrics(track: Track, item: LyricsSearch.Result) {
        val panel =
            LinearLayout(this).apply {
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
            AlertDialog.Builder(this)
                .setTitle("Проверить текст")
                .setView(ScrollView(this).apply { addView(panel) })
                .setPositiveButton(afp(R.string.save), null)
                .setNegativeButton(afp(R.string.cancel), null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = item.text.isNotBlank()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                runAsync({
                    lyricStore.save(track.uri, item.text)
                    prefs
                        .edit()
                        .putString(
                            "lyricsSource:${track.uri}",
                            "Источник: LRCLIB · ${item.title} — ${item.artist}",
                        )
                        .apply()
                }) { result ->
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
        dialog.show()
    }
