package dev.alex.afponline

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Foreground-only diagnostic player. Session stays in memory, never in an Intent or report. */
class ZvukLabActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var future: Future<*>? = null
    private var generation = 0
    private var closed = false
    private var client: ZvukClient? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var connected = false
    private var busy = false
    private lateinit var status: TextView
    private lateinit var token: EditText
    private lateinit var query: EditText
    private lateinit var list: LinearLayout
    private val actions = mutableListOf<Button>()
    private val evidence =
        linkedMapOf(
            "session" to "not_run",
            "collection" to "not_run",
            "search" to "not_run",
            "stream" to "not_run",
            "playback" to "not_run",
        )
    private val audio by lazy { getSystemService(AudioManager::class.java) }
    private val attributes =
        AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        title = "Звук · эксперимент"
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
            }
        setContentView(ScrollView(this).apply { addView(root) })
        fun label(text: String) =
            TextView(this).apply {
                this.text = text
                setPadding(0, 12, 0, 12)
                root.addView(this)
            }
        fun button(text: String, gated: Boolean = false, action: () -> Unit): Button {
            return Button(this).apply {
                this.text = text
                setOnClickListener { action() }
                root.addView(this)
                if (gated) actions += this
            }
        }
        label(
            "Пререлизный стенд AFP Online. Здесь проверяем реальный доступ к Звуку. Встроенный вход через Сбер ID пока не реализован."
        )
        button("Открыть Звук для входа") {
            runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zvuk.com/")))
                }
                .onFailure { status.text = "Не удалось открыть браузер" }
        }
        label(
            "Для этого эксперимента нужен токен собственной сессии Звука. Он вводится только здесь и хранится до закрытия экрана. Не отправляй его в чат."
        )
        button("Как получить токен") {
            AlertDialog.Builder(this)
                .setTitle("Локальный эксперимент")
                .setMessage(
                    "Войди на zvuk.com в своём браузере. В том же браузере открой https://zvuk.com/api/tiny/profile и скопируй значение token. Вставь его в поле ниже. Это ручной диагностический способ, не автоматический вход через Сбер ID."
                )
                .setPositiveButton("Открыть профиль") { _, _ ->
                    runCatching {
                            startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://zvuk.com/api/tiny/profile"),
                                )
                            )
                        }
                        .onFailure { status.text = "Не удалось открыть браузер" }
                }
                .setNegativeButton("Закрыть", null)
                .show()
        }
        token =
            EditText(this).apply {
                hint = "Токен сессии · локально"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                isSingleLine = true
                isSaveEnabled = false
                importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                root.addView(this)
            }
        button("Подключить и проверить") {
            if (!busy) {
                disconnect()
                evidence.keys.toList().forEach { evidence[it] = "not_run" }
                try {
                    client = ZvukClient(token.text.toString().trim())
                } catch (e: ZvukClient.Problem) {
                    status.text = e.reason
                    return@button
                }
                token.text.clear()
                execute(
                    "Проверяем сессию…",
                    "session",
                    {
                        client!!.checkSession()
                        true
                    },
                ) {
                    connected = true
                    status.text = "Сессия подтверждена. Доступ к музыке проверяется отдельно"
                    updateActions()
                }
            }
        }
        status = label("Не подключено")
        query =
            EditText(this).apply {
                hint = "Песня или исполнитель"
                isSingleLine = true
                root.addView(this)
            }
        button("Поиск", true) {
            val value = query.text.toString()
            execute("Ищем…", "search", { client!!.search(value) }, ::showSongs)
        }
        button("Моя коллекция · первые 20", true) {
            execute("Читаем коллекцию…", "collection", { client!!.collection() }, ::showSongs)
        }
        button("Остановить") {
            cancelWork()
            stopPlayback()
            status.text = "Остановлено"
        }
        button("Отключить и удалить сессию") {
            disconnect()
            status.text = "Локальная сессия удалена"
        }
        button("Результат проверки") {
            AlertDialog.Builder(this)
                .setTitle("Результат без личных данных")
                .setMessage(evidence.entries.joinToString("\n") { "${it.key}: ${it.value}" })
                .setPositiveButton("Закрыть", null)
                .show()
        }
        list =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                root.addView(this)
            }
        updateActions()
    }

    private fun updateActions() {
        actions.forEach { it.isEnabled = connected && !busy }
    }

    private fun <T> execute(message: String, stage: String, operation: () -> T, done: (T) -> Unit) {
        if (closed || busy) return
        val id = ++generation
        busy = true
        status.text = message
        updateActions()
        future =
            worker.submit {
                val result = runCatching(operation)
                main.post {
                    if (closed || id != generation) return@post
                    busy = false
                    updateActions()
                    result
                        .onSuccess {
                            evidence[stage] = "passed"
                            done(it)
                        }
                        .onFailure {
                            evidence[stage] = "failed"
                            status.text =
                                (it as? ZvukClient.Problem)?.reason
                                    ?: "Не удалось выполнить проверку"
                        }
                }
            }
    }

    private fun showSongs(songs: List<ZvukClient.Song>) {
        list.removeAllViews()
        status.text =
            if (songs.isEmpty()) "Треки не найдены" else "Выбери трек для проверки воспроизведения"
        songs.forEach { song ->
            list.addView(
                Button(this).apply {
                    text = "${song.title}\n${song.artist}"
                    setOnClickListener {
                        if (!connected || busy) return@setOnClickListener
                        stopPlayback()
                        execute("Получаем поток…", "stream", { client!!.stream(song.id) }) { uri ->
                            AlertDialog.Builder(this@ZvukLabActivity)
                                .setTitle("Проверить воспроизведение?")
                                .setMessage(
                                    "Поток с ${uri.host}. Токен аккаунта на этот сервер не передаётся. Качество: mid. Проигрывание остановится при уходе с этого экрана."
                                )
                                .setPositiveButton("Слушать") { _, _ -> play(uri) }
                                .setNegativeButton("Отмена", null)
                                .show()
                        }
                    }
                }
            )
        }
    }

    private fun play(uri: URI) {
        stopPlayback()
        val request =
            AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener { change ->
                    if (change < 0) {
                        stopPlayback()
                        status.text = "Проигрывание остановлено: сменился аудиофокус"
                    }
                }
                .build()
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            status.text = "Аудиофокус не получен"
            return
        }
        focus = request
        val media = MediaPlayer()
        player = media
        try {
            media.setAudioAttributes(attributes)
            // Context/Uri uses no account headers; only this validated temporary HTTPS URL.
            media.setDataSource(uri.toString())
            media.setOnPreparedListener {
                if (player === it && !closed) {
                    it.start()
                    status.text = "Играет поток Звука · эксперимент"
                    evidence["playback"] = "started_not_yet_completed"
                }
            }
            media.setOnErrorListener { _, _, _ ->
                evidence["playback"] = "failed"
                stopPlayback()
                status.text =
                    "Поток не воспроизведён. Возможны ограничения доступа, формата или сети"
                true
            }
            media.setOnCompletionListener {
                evidence["playback"] = "completed"
                stopPlayback()
                status.text = "Воспроизведение завершено"
            }
            status.text = "Подключаем аудиопоток…"
            media.prepareAsync()
        } catch (_: Exception) {
            evidence["playback"] = "failed"
            stopPlayback()
            status.text = "Не удалось открыть аудиопоток"
        }
    }

    private fun stopPlayback() {
        val old = player
        player = null
        old?.setOnPreparedListener(null)
        old?.setOnErrorListener(null)
        old?.setOnCompletionListener(null)
        runCatching { old?.release() }
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun cancelWork() {
        generation++
        future?.cancel(true)
        future = null
        client?.cancel()
        busy = false
        updateActions()
    }

    private fun disconnect() {
        cancelWork()
        stopPlayback()
        client = null
        connected = false
        if (::list.isInitialized) list.removeAllViews()
        updateActions()
    }

    override fun onStop() {
        cancelWork()
        stopPlayback()
        if (::status.isInitialized)
            status.text =
                if (connected) "Подключено · воспроизведение остановлено" else "Не подключено"
        super.onStop()
    }

    override fun onDestroy() {
        closed = true
        disconnect()
        token.text.clear()
        worker.shutdownNow()
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
