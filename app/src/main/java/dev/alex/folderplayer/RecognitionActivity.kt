package dev.alex.folderplayer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/** Explicit foreground-only recognition. Recording never touches disk. */
class RecognitionActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var start: Button
    private lateinit var stop: Button
    private lateinit var results: LinearLayout
    private lateinit var lyricsChoices: LinearLayout
    @Volatile private var cancelled = true
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var connection: HttpURLConnection? = null
    private var task: java.util.concurrent.Future<*>? = null
    private var lyricsCancel: LyricsSearch.Cancel? = null
    private var generation = 0
    private var cacheTitle = ""
    private var cacheArtist = ""
    private var cachedRead: Button? = null
    private var localUri = ""
    private var alive = true
    private val history by lazy { getSharedPreferences("recognition", 0) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        localUri = intent.getStringExtra("localUri").orEmpty()
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }
        panel.addView(
            TextView(this).apply {
                text = "Определить песню"
                textSize = 24f
            }
        )
        panel.addView(
            TextView(this).apply {
                text =
                    if (localUri.isBlank())
                        "Поднеси телефон к музыке. Запись — до 30 секунд. Для определения нужен интернет."
                    else
                        "Определяем текущий файл по звуковому отпечатку. Микрофон не нужен; для определения нужен интернет."
            }
        )
        status =
            TextView(this).apply {
                text = "Готово к прослушиванию"
                textSize = 18f
            }
        panel.addView(status)
        start =
            Button(this).apply {
                text = if (localUri.isBlank()) "Слушать" else "Определить текущий трек"
                setOnClickListener { if (localUri.isBlank()) begin() else beginFile() }
            }
        panel.addView(start)
        stop =
            Button(this).apply {
                text = "Отмена"
                isEnabled = false
                setOnClickListener {
                    cancel()
                    status.text = "Действие отменено"
                }
            }
        panel.addView(stop)
        results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(results)
        panel.addView(
            Button(this).apply {
                text = "История"
                setOnClickListener { showHistory() }
            }
        )
        panel.addView(
            Button(this).apply {
                text = "О распознавании"
                setOnClickListener {
                    AlertDialog.Builder(this@RecognitionActivity)
                        .setTitle("Распознавание музыки")
                        .setMessage(
                            (if (localUri.isBlank())
                                "После нажатия «Слушать» микрофон записывает до 30 секунд."
                            else
                                "Из текущего трека читается фрагмент до 30 секунд. Для онлайн-трека он запрашивается у Яндекса и обрабатывается на устройстве. Микрофон не используется.") +
                                " На сервер Shazam отправляется звуковой отпечаток; сам звук, имя файла и геолокация не отправляются. Аудио не сохраняется. Используется неофициальный интерфейс сервиса: его доступность может меняться."
                        )
                        .setPositiveButton("Закрыть", null)
                        .show()
                }
            }
        )
        setContentView(ScrollView(this).apply { addView(panel) })
        if (localUri.isNotBlank()) main.post { if (alive) beginFile() }
    }

    private fun begin() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
            return
        }
        cancel()
        val token = generation
        cancelled = false
        start.isEnabled = false
        stop.isEnabled = true
        results.removeAllViews()
        task =
            worker.submit {
                val outcome = runCatching {
                    val minimum =
                        AudioRecord.getMinBufferSize(
                            16000,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                        )
                    check(minimum > 0)
                    val audio =
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            16000,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            maxOf(minimum, 8192),
                        )
                    recorder = audio
                    val samples = ShortArray(30 * 16000)
                    try {
                        check(audio.state == AudioRecord.STATE_INITIALIZED)
                        checkActive(token)
                        audio.startRecording()
                        check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING)
                        var count = 0
                        var last = -1
                        while (count < samples.size) {
                            checkActive(token)
                            val n =
                                audio.read(
                                    samples,
                                    count,
                                    minOf(4096, samples.size - count),
                                    AudioRecord.READ_BLOCKING,
                                )
                            check(n > 0)
                            count += n
                            val seconds = count / 16000
                            if (seconds != last) {
                                last = seconds
                                ui(token) { status.text = "Слушаем… $seconds / 30 с" }
                            }
                        }
                    } finally {
                        runCatching { audio.stop() }
                        audio.release()
                        if (recorder === audio) recorder = null
                    }
                    ui(token) { status.text = "Определяем песню…" }
                    try {
                        identifySamples(samples, token)
                    } finally {
                        samples.fill(0)
                    }
                }
                ui(token) {
                    cancelled = true
                    start.isEnabled = true
                    stop.isEnabled = false
                    outcome
                        .onSuccess { track ->
                            if (track == null)
                                status.text =
                                    "Песню не удалось определить. Попробуй другой фрагмент."
                            else {
                                val title = track.optString("title").take(300)
                                val artist = track.optString("subtitle").take(300)
                                status.text = "Песня найдена"
                                save(title, artist)
                                if (localUri.isNotBlank())
                                    runCatching {
                                        SearchIdentity(this)
                                            .save(
                                                localUri,
                                                SongQuery(title.take(200), artist.take(200)),
                                            )
                                    }
                                showResult(title, artist)
                            }
                        }
                        .onFailure {
                            status.text =
                                "Не удалось определить песню. Проверь интернет и попробуй ещё раз."
                        }
                }
            }
    }

    private fun identifySamples(samples: ShortArray, token: Int): JSONObject? {
        for (offset in intArrayOf(0, 12 * 16000, 24 * 16000)) {
            checkActive(token)
            if (samples.size - offset < 3 * 16000) continue
            val segment = samples.copyOfRange(offset, minOf(offset + 12 * 16000, samples.size))
            val signature =
                try {
                    MusicFingerprint.encode(segment)
                } finally {
                    segment.fill(0)
                }
            val data = identify(signature, minOf(12 * 16000, samples.size - offset), token)
            val track = data.optJSONObject("track")
            if (
                track != null &&
                    track.optString("title").isNotBlank() &&
                    (data.optJSONArray("matches")?.length() ?: 0) > 0
            )
                return track
        }
        return null
    }

    private fun beginFile() {
        cancel()
        val token = generation
        cancelled = false
        start.isEnabled = false
        stop.isEnabled = true
        results.removeAllViews()
        status.text = "Читаем фрагмент файла…"
        task =
            worker.submit {
                val outcome = runCatching {
                    val cue=Library(this).tracks().firstOrNull { it.uri==localUri && it.cueSheetUri.isNotBlank() }
                    val samples =
                        LocalAudio.decode(
                            this,
                            if(localUri.startsWith("afp-yandex://")) OnlinePlayback(this).resolve(android.net.Uri.parse(localUri)).toString() else cue?.sourceUri ?: localUri,
                            intent.getLongExtra("localPosition", 0),
                            intent.getLongExtra("localDuration", 0),
                            sourceOffsetMs=cue?.cueStartMs ?: 0,
                        ) {
                            checkActive(token)
                        }
                    try {
                        ui(token) { status.text = "Определяем текущий трек…" }
                        identifySamples(samples, token)
                    } finally {
                        samples.fill(0)
                    }
                }
                ui(token) {
                    cancelled = true
                    start.isEnabled = true
                    stop.isEnabled = false
                    outcome
                        .onSuccess { song ->
                            if (song == null)
                                status.text =
                                    "Песню не удалось определить. Попробуй другой фрагмент."
                            else {
                                val title = song.optString("title").take(200)
                                val artist = song.optString("subtitle").take(200)
                                save(title, artist)
                                runCatching {
                                    SearchIdentity(this).save(localUri, SongQuery(title, artist))
                                }
                                status.text = "Текущий трек определён"
                                showResult(title, artist)
                            }
                        }
                        .onFailure {
                            status.text =
                                it.message?.takeIf { message ->
                                    message in
                                        setOf(
                                            "Слишком короткий фрагмент для определения",
                                            "Звуковая дорожка не найдена",
                                            "Неподдерживаемый формат аудио",
                                            "Чтение фрагмента заняло слишком долго",
                                        )
                                } ?: "Не удалось определить файл. Проверь доступ к нему и интернет."
                        }
                }
            }
    }

    private fun checkActive(token: Int) {
        if (cancelled || token != generation || Thread.currentThread().isInterrupted())
            throw java.util.concurrent.CancellationException()
    }

    private fun ui(token: Int, action: () -> Unit) {
        main.post { if (alive && token == generation) action() }
    }

    private fun identify(signature: String, samples: Int, token: Int): JSONObject {
        checkActive(token)
        val c =
            URL(
                    "https://amp.shazam.com/discovery/v5/en/US/android/-/tag/${UUID.randomUUID()}/${UUID.randomUUID()}"
                )
                .openConnection() as HttpURLConnection
        connection = c
        try {
            c.connectTimeout = 10000
            c.readTimeout = 20000
            c.instanceFollowRedirects = false
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("User-Agent", "Shazam/14.0.0 (Android)")
            c.setRequestProperty("Content-Language", "en_US")
            val now = System.currentTimeMillis()
            val body =
                JSONObject()
                    .put(
                        "signature",
                        JSONObject()
                            .put("samplems", samples * 1000 / 16000)
                            .put("timestamp", now)
                            .put("uri", signature),
                    )
                    .put("timestamp", now)
                    .put("timezone", "UTC")
                    .toString()
                    .toByteArray(Charsets.UTF_8)
            c.setFixedLengthStreamingMode(body.size)
            checkActive(token)
            c.outputStream.use { it.write(body) }
            check(c.responseCode == 200)
            val bytes = java.io.ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    checkActive(token)
                    val n = input.read(buffer)
                    if (n < 0) break
                    check(bytes.size() + n <= 2 * 1024 * 1024)
                    bytes.write(buffer, 0, n)
                }
            }
            checkActive(token)
            return JSONObject(bytes.toString("UTF-8"))
        } finally {
            c.disconnect()
            if (connection === c) connection = null
        }
    }

    @JvmOverloads
    private fun showResult(
        title: String,
        artist: String,
        cacheName: String = title,
        cachePerformer: String = artist,
    ) {
        cacheTitle = cacheName
        cacheArtist = cachePerformer
        results.removeAllViews()
        results.addView(
            TextView(this).apply {
                text = "$title\n$artist"
                textSize = 22f
            }
        )
        results.addView(
            Button(this).apply {
                text = "Найти текст"
                setOnClickListener { findLyrics(title, artist) }
            }
        )
        cachedRead = null
        refreshSavedText()
        lyricsChoices = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        results.addView(lyricsChoices)
        results.addView(
            Button(this).apply {
                text = "Исправить запрос"
                setOnClickListener { editQuery(title, artist) }
            }
        )
        results.addView(
            Button(this).apply {
                text = "Поиск в интернете"
                setOnClickListener { LyricsWeb.open(this@RecognitionActivity, title, artist) }
            }
        )
        results.addView(
            Button(this).apply {
                text = "Вставить текст"
                setOnClickListener { enterText() }
            }
        )
    }

    private fun refreshSavedText() {
        if (
            cachedRead != null ||
                cacheTitle.isBlank() ||
                !::results.isInitialized ||
                RecognizedTextStore(this).read(cacheTitle, cacheArtist) == null
        )
            return
        cachedRead =
            Button(this).apply {
                text = "Читать сохранённый текст"
                setOnClickListener {
                    startActivity(
                        Intent(this@RecognitionActivity, SongTextActivity::class.java)
                            .putExtra("songTitle", cacheTitle)
                            .putExtra("artist", cacheArtist)
                            .putExtra("cacheTitle", cacheTitle)
                            .putExtra("cacheArtist", cacheArtist)
                    )
                }
            }
        results.addView(cachedRead, minOf(2, results.childCount))
    }

    override fun onResume() {
        super.onResume()
        refreshSavedText()
    }

    private fun editQuery(title: String, artist: String) {
        cancel()
        status.text = "Можно уточнить запрос"
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val name =
            EditText(this).apply {
                hint = "Название песни"
                setText(title)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(name)
        val performer =
            EditText(this).apply {
                hint = "Исполнитель (можно оставить пустым)"
                setText(artist)
                isSingleLine = true
                filters = arrayOf(android.text.InputFilter.LengthFilter(200))
            }
        panel.addView(performer)
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Поиск текста")
                .setView(panel)
                .setPositiveButton("Поиск", null)
                .setNegativeButton("Закрыть", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (name.text.isBlank()) {
                    name.error = "Введи название песни"
                    return@setOnClickListener
                }
                val t = name.text.toString().trim()
                val a = performer.text.toString().trim()
                dialog.dismiss()
                showResult(t, a, cacheTitle, cacheArtist)
                findLyrics(t, a)
            }
        }
        dialog.show()
    }

    private fun enterText() {
        cancel()
        status.text = "Можно добавить текст"
        val field =
            EditText(this).apply {
                hint = "Вставь или введи слова песни"
                minLines = 6
                gravity = android.view.Gravity.TOP
                filters = arrayOf(android.text.InputFilter.LengthFilter(20000))
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Слова песни")
                .setView(field)
                .setPositiveButton("Открыть", null)
                .setNegativeButton("Закрыть", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (field.text.isBlank()) {
                    field.error = "Добавь текст"
                    return@setOnClickListener
                }
                val source = field.text.toString()
                dialog.dismiss()
                openText(source, cacheTitle, cacheArtist)
            }
        }
        dialog.show()
    }

    private fun openText(source: String, title: String = "", artist: String = "") {
        startActivity(
            Intent(this, SongTextActivity::class.java)
                .putExtra("source", source)
                .putExtra("songTitle", title)
                .putExtra("artist", artist)
                .putExtra("cacheTitle", cacheTitle)
                .putExtra("cacheArtist", cacheArtist)
        )
    }

    private fun showLyrics(found: List<LyricsSearch.Result>, requestedArtist: String) {
        lyricsChoices.removeAllViews()
        val unique =
            found
                .filter { it.text.isNotBlank() }
                .distinctBy {
                    LyricsSearch.clean(it.artist) +
                        "|" +
                        LyricsDocument(it.text).plain.replace(Regex("\\s+"), " ")
                }
                .take(12)
        if (unique.isEmpty()) {
            status.text = "Текст не найден. Уточни запрос или поищи в интернете."
            return
        }
        status.text = "Найдено текстов: ${unique.size}. Выбери подходящий."
        unique.forEach { item ->
            val other =
                LyricsSearch.clean(item.artist) != LyricsSearch.clean(requestedArtist) &&
                    requestedArtist.isNotBlank()
            lyricsChoices.addView(
                Button(this).apply {
                    text =
                        "${item.title} — ${item.artist}" +
                            (if (other) "\nДругая версия — проверь слова" else "")
                    setOnClickListener {
                        if (localUri.isNotBlank())
                            runCatching {
                                SearchIdentity(this@RecognitionActivity)
                                    .save(localUri, SongQuery(item.title, item.artist))
                                LyricsStore(this@RecognitionActivity).save(localUri, item.text)
                            }
                        openText(LyricsDocument(item.text).plain, item.title, item.artist)
                    }
                }
            )
        }
    }

    private val lyricsSearch by lazy { LyricsSearch(SearchConnections(this)) }

    private fun findLyrics(title: String, artist: String) {
        cancel()
        val token = generation
        cancelled = false
        start.isEnabled = false
        stop.isEnabled = true
        status.text = "Ищем текст…"
        if (::lyricsChoices.isInitialized) lyricsChoices.removeAllViews()
        val signal = LyricsSearch.Cancel()
        lyricsCancel = signal
        task =
            worker.submit {
                val outcome = runCatching {
                    TrackSearch(this, lyricsSearch)
                        .search(
                            Track(
                                localUri,
                                title,
                                artist,
                                intent.getLongExtra("localDuration", 0),
                                "",
                            ),
                            SongQuery(title, artist),
                            signal,
                        )
                }
                ui(token) {
                    cancelled = true
                    start.isEnabled = true
                    stop.isEnabled = false
                    outcome
                        .onSuccess { showLyrics(it, artist) }
                        .onFailure {
                            status.text =
                                "Не удалось выполнить поиск. Проверь подключение или попробуй позже."
                        }
                }
            }
    }

    private fun save(title: String, artist: String) {
        val old =
            runCatching { JSONArray(history.getString("items", "[]")) }.getOrDefault(JSONArray())
        val next = JSONArray().put(JSONObject().put("title", title).put("artist", artist))
        for (i in 0 until old.length()) {
            val item = old.optJSONObject(i) ?: continue
            if (item.optString("title") == title && item.optString("artist") == artist) continue
            if (next.length() < 50) next.put(item)
        }
        history.edit().putString("items", next.toString()).apply()
    }

    private fun showHistory() {
        cancel()
        status.text = "История распознавания"
        fun rows() =
            runCatching { JSONArray(history.getString("items", "[]")) }.getOrDefault(JSONArray())
        fun id(row: JSONObject) =
            LyricsDocument.key(row.optString("title") + "\n" + row.optString("artist"))
        val removed = mutableMapOf<String, Pair<JSONObject, Int>>()
        HistoryDialog.show(
            this,
            "Определённые песни",
            {
                val all = rows()
                (0 until all.length()).map { i ->
                    val r = all.getJSONObject(i)
                    HistoryDialog.Entry(id(r), "${r.optString("title")} — ${r.optString("artist")}")
                }
            },
            { key ->
                val all = rows()
                (0 until all.length())
                    .map { all.getJSONObject(it) }
                    .firstOrNull { id(it) == key }
                    ?.let {
                        status.text = "Из истории"
                        showResult(it.optString("title"), it.optString("artist"))
                    }
            },
            { key ->
                val all = rows()
                val next = JSONArray()
                for (i in 0 until all.length()) {
                    val r = all.getJSONObject(i)
                    if (id(r) == key) removed[key] = r to i else next.put(r)
                }
                history.edit().putString("items", next.toString()).commit()
            },
            { key ->
                removed[key]?.let { (row, index) ->
                    val all = rows()
                    val values =
                        (0 until all.length()).map { all.getJSONObject(it) }.toMutableList()
                    if (values.none { id(it) == key })
                        values.add(index.coerceIn(0, values.size), row)
                    history.edit().putString("items", JSONArray(values).toString()).commit()
                } ?: false
            },
        )
    }

    private fun cancel() {
        generation++
        cancelled = true
        lyricsCancel?.cancel()
        lyricsCancel = null
        connection?.disconnect()
        runCatching { recorder?.stop() }
        task?.cancel(true)
        task = null
        if (::start.isInitialized) {
            start.isEnabled = true
            stop.isEnabled = false
        }
    }

    override fun onRequestPermissionsResult(
        code: Int,
        permissions: Array<out String>,
        grants: IntArray,
    ) {
        super.onRequestPermissionsResult(code, permissions, grants)
        if (code == 1) {
            if (grants.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin()
            else status.text = "Для прослушивания разреши доступ к микрофону"
        }
    }

    override fun onStop() {
        val busy = !cancelled
        cancel()
        if (busy && ::status.isInitialized) status.text = "Действие остановлено. Можно повторить."
        super.onStop()
    }

    override fun onDestroy() {
        alive = false
        cancel()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
