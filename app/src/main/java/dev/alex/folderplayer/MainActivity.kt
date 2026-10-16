package dev.alex.folderplayer

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var screens: PlayerScreens
    private var page = "library"
    private lateinit var pageMotion: SheetMotion
    private var soundReturn = "player"
    private var lyricsRequestedId: String? = null
    private lateinit var lyricsWork: RetainedOperation<List<LyricsSearch.Result>>
    private var directId: String? = null
    private var directResults = emptyList<LyricsSearch.Result>()
    private var mediaGeneration = 0
    private var mediaLoadedId: String? = null
    private var queueTimeline: androidx.media3.common.Timeline? = null
    private var queueTracks: List<Track>? = null
    private lateinit var library: Library
    private lateinit var layout: LinearLayout
    private lateinit var list: LinearLayout
    private lateinit var now: TextView
    private lateinit var position: TextView
    private lateinit var seek: SeekBar
    private lateinit var play: Button
    private lateinit var selectionButton: Button
    private lateinit var bulkButton: Button
    private lateinit var moreTools: LinearLayout
    private lateinit var browserTitle: TextView
    private lateinit var browserCount: TextView
    private lateinit var search: EditText
    private var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var tracks = emptyList<Track>()
    private var visible = emptyList<Track>()
    private var selectedPlaylist: String? = null
    private var query = ""
    private val selection = linkedSetOf<String>()
    private var selecting = false
    private var lyricTarget: String? = null
    private var lyricUpdate: (() -> Unit)? = null
    private val lyricsSearch by lazy {
        LyricsSearch(SearchConnections(this)).also {
            it.restoreCooldown(prefs.getLong("lyricsRetryUntil", 0))
        }
    }
    private val translationClient by lazy { TranslationClient(this) }
    private lateinit var translationWork: RetainedOperation<SongTranslationResult>
    private var translationId: String? = null
    private val networkExecutor = Executors.newSingleThreadExecutor()
    private var networkCancel: LyricsSearch.Cancel? = null
    private var networkTask: java.util.concurrent.Future<*>? = null
    private val albumYears by lazy {
        AlbumYearDialogs(
            this,
            {
                tracks = library.tracks()
                render()
                prefs
                    .getString("queueSource", null)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { reorderPlayingQueue(it) }
            },
        )
    }
    private val lyricStore by lazy { LyricsStore(this) }
    private var scanning = false
    private var dragging = false
    private var alive = true
    private val trackLabels = linkedMapOf<String, TextView>()
    private var highlightedId: String? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var screenStarted = false
    private val ticker =
        object : Runnable {
            override fun run() {
                if (!screenStarted) return
                updatePlayer()
                lyricUpdate?.invoke()
                if (controller?.isPlaying == true)
                    handler.postDelayed(
                        this,
                        if (page == "lyrics" || lyricUpdate != null) 500 else 1000,
                    )
            }
        }

    private fun refreshTicker() {
        handler.removeCallbacks(ticker)
        if (screenStarted) handler.post(ticker)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val retained = lastNonConfigurationInstance as? MainOperations
        translationWork = retained?.translation ?: RetainedOperation()
        lyricsWork = retained?.lyrics ?: RetainedOperation()
        soundReturn = state?.getString("soundReturn") ?: "player"
        lyricTarget = state?.getString("lyricTarget")
        library = Library(this)
        tracks = library.tracks()
        query = state?.getString("libraryQuery").orEmpty()
        selectedPlaylist = state?.getString("selectedPlaylist")
        selecting = state?.getBoolean("selecting") ?: false
        selection.addAll(state?.getStringArrayList("selection").orEmpty())
        layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(PlayerScreens.bg)
            }
        if (android.os.Build.VERSION.SDK_INT >= 30)
            layout.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(16, 12 + bars.top, 16, 8 + bars.bottom)
                insets
            }
        root = FrameLayout(this)
        root.addView(layout)
        screens =
            PlayerScreens(
                this,
                object : PlayerScreens.Actions {
                    override fun back() {
                        backPage()
                    }

                    override fun open(page: String) {
                        if (page == "lyrics") openLyrics() else showPage(page)
                    }

                    override fun toggle() {
                        controller?.let { if (it.isPlaying) it.pause() else { if(it.playbackState==Player.STATE_IDLE)it.prepare();it.play() } }
                        updatePlayer()
                    }

                    override fun previous() {
                        controller?.seekToPreviousMediaItem()
                    }

                    override fun next() {
                        controller?.seekToNextMediaItem()
                    }

                    override fun skip(direction: Int) {
                        this@MainActivity.skip(direction)
                    }

                    override fun seek(progress: Int) {
                        controller?.let {
                            if (it.duration > 0 && it.duration != C.TIME_UNSET)
                                it.seekTo(it.duration * progress / 1000)
                        }
                    }

                    override fun favorite() {
                        currentTrack()?.let {
                            val item=controller?.currentMediaItem
                            val online=item?.let { media -> OnlineQueueMetadata.row(media) }
                            if(online!=null) {
                                val actions=OnlineLibraryActions(this@MainActivity)
                                actions.set("favorites",org.json.JSONArray().put(online),!actions.contains("favorites",online.optString("id")))
                            } else library.toggleFavorite(it)
                            render()
                            updatePlayer()
                        }
                    }

                    override fun playlists() {
                        currentTrack()?.let { addToPlaylists(it) }
                    }

                    override fun repeat() {
                        repeatDialog()
                    }

                    override fun sound() {
                        equalizer()
                    }

                    override fun recognize() {
                        recognitionMenu()
                    }

                    override fun installTranslationModel() {
                        extensions()
                    }

                    override fun documentTranslation() {
                        val track = currentTrack()
                        cancelTranslation()
                        translationId = track?.uri
                        executor.execute {
                            val result = runCatching {
                                track?.let { loadLyrics(it) }?.let { LyricsDocument(it).plain }
                                    ?: ""
                            }
                            handler.post {
                                if (alive)
                                    result
                                        .onSuccess {
                                            if (track != null && currentTrack()?.uri == track.uri)
                                                startActivityForResult(
                                                    Intent(
                                                            this@MainActivity,
                                                            SongTextActivity::class.java,
                                                        )
                                                        .putExtra("source", it)
                                                        .putExtra("songTitle", track.title)
                                                        .putExtra("artist", track.artist)
                                                        .putExtra("localUri", track.uri)
                                                        .putExtra("openTranslation", true),
                                                    107,
                                                )
                                        }
                                        .onFailure {
                                            toast(it.message ?: "Не удалось прочитать текст")
                                        }
                            }
                        }
                    }

                    override fun retryTranslation() {
                        currentTrack()?.let { translate(it, true) }
                    }

                    override fun renewTranslation() {
                        currentTrack()?.let { renewTranslation(it) }
                    }

                    override fun songLanguage() {
                        currentTrack()?.let { editSongLanguage(it) }
                    }

                    override fun retryLyrics() {
                        currentTrack()?.let { directLyrics(it) }
                    }

                    override fun chooseLyrics(index: Int) {
                        val track = currentTrack()
                        val result = directResults.getOrNull(index)
                        if (track != null && track.uri == directId && result != null)
                            saveDirectLyrics(track, result)
                    }

                    override fun lyricsMenu() {
                        currentTrack()?.let { currentSongMenu(it) } ?: toast("Сначала включи песню")
                    }

                    override fun select(index: Int) {
                        controller?.let {
                            if (index in 0 until it.mediaItemCount) {
                                it.seekTo(index, 0)
                                it.play()
                            }
                        }
                    }

                    override fun move(from: Int, to: Int) {
                        controller?.let {
                            if (
                                from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount
                            ) {
                                it.moveMediaItem(from, to)
                                queueTimeline = null
                                updatePlayer()
                            }
                        }
                    }
                },
            )
        root.addView(screens.view, FrameLayout.LayoutParams(-1, -1))
        screens.view.visibility = View.GONE
        pageMotion = SheetMotion(screens.view)
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            layout.setOnApplyWindowInsetsListener(null)
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        }
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (page != "library") backPage() else finish()
            }
        browserTitle = label("Библиотека", 24f)
        layout.addView(browserTitle)
        browserCount = label("", 13f).apply { setTextColor(PlayerScreens.muted) }
        layout.addView(browserCount)
        val sources = row()
        sources.addView(button("На устройстве") { selectedPlaylist=null;render() },weight())
        sources.addView(button("Яндекс Музыка") { onlineSection.show() },weight())
        layout.addView(sources)
        val tools = row()
        moreTools = row()
        fun command(kind: IconButton.Kind, label: String, action: () -> Unit) =
            IconButton(this, kind, label, action)
        tools.addView(
            command(IconButton.Kind.LIBRARY, "Библиотека") {
                selectedPlaylist = null
                selecting = false
                selection.clear()
                render()
            },
            weight().apply { height = dp(48) },
        )
        tools.addView(
            command(IconButton.Kind.PLAYLIST, "Плейлисты") { choosePlaylist() },
            weight().apply { height = dp(48) },
        )
        tools.addView(
            command(IconButton.Kind.SORT, "Сортировка") { sortDialog() },
            weight().apply { height = dp(48) },
        )
        selectionButton =
            command(IconButton.Kind.SELECT, "Выбрать треки") {
                selecting = !selecting
                selection.clear()
                render()
            }
        tools.addView(selectionButton, weight().apply { height = dp(48) })
        tools.addView(
            command(IconButton.Kind.MENU, "Меню") { settings() },
            weight().apply { height = dp(48) },
        )
        bulkButton = button("В плейлист") { bulkAdd() }
        moreTools.addView(bulkButton, weight().apply { height = dp(48) })
        layout.addView(tools)
        layout.addView(moreTools)
        val quick = row()
        quick.addView(
            button("Определить песню") { identifySong() },
            LinearLayout.LayoutParams(0, dp(48), 2f),
        )
        quick.addView(button("Обновить") { scan() }, LinearLayout.LayoutParams(0, dp(48), 1f))
        layout.addView(quick)
        search =
            EditText(this).apply {
                setText(query)
                hint = "Найти песню или исполнителя"
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
                isSingleLine = true
            }
        search.addTextChangedListener(
            object : android.text.TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) {}

                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s.toString()
                    render()
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        layout.addView(search)
        val scroller = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(list)
        layout.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val current =
            NowPlayingStrip(
                this,
                { travel -> previewPlayer(travel) },
                { if (page == "library") pageMotion.show(false, root.height, true) },
            ) {
                showPage("player")
            }
        now = current.title
        position = current.time
        layout.addView(current, LinearLayout.LayoutParams(-1, -2))
        seek =
            SeekBar(this).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(PlayerScreens.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(PlayerScreens.accent)
            }
        layout.addView(seek)
        seek.setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar: SeekBar) {
                    dragging = true
                }

                override fun onStopTrackingTouch(bar: SeekBar) {
                    controller?.let {
                        if (it.duration > 0) it.seekTo(it.duration * bar.progress / 1000)
                    }
                    dragging = false
                }

                override fun onProgressChanged(bar: SeekBar, value: Int, user: Boolean) {}
            }
        )
        val controls = row()
        controls.addView(button("⏮") { controller?.seekToPreviousMediaItem() }, weight())

        play = button("▶") { controller?.let { if (it.isPlaying) it.pause() else { if(it.playbackState==Player.STATE_IDLE)it.prepare();it.play() } } }
        controls.addView(play, weight())
        controls.addView(button("⏭") { controller?.seekToNextMediaItem() }, weight())
        layout.addView(controls)
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val f = MediaController.Builder(this, token).buildAsync()
        future = f
        f.addListener(
            {
                if (alive)
                    runCatching {
                            controller = f.get()
                            controller?.addListener(
                                object : Player.Listener {
                                    override fun onEvents(player: Player, events: Player.Events) {
                                        updatePlayer()
                                        refreshTicker()
                                    }

                                    override fun onPlayerError(error: PlaybackException) {
                                        val onlineError = generateSequence(error as Throwable?) { it.cause }
                                            .take(8).filterIsInstance<OnlinePlayback.Problem>().firstOrNull()
                                        toast(onlineError?.message ?: "Не удалось воспроизвести трек: ${error.errorCodeName}")
                                    }
                                }
                            )
                            restorePlayingSource()
                            updatePlayer()
                        }
                        .onFailure { toast("Не удалось подключить плеер") }
            },
            java.util.concurrent.Executor { handler.post(it) },
        )
        render()
        showPage(
            state?.getString("uiPage") ?: prefs.getString("uiPage", "library").orEmpty(),
            false,
        )
    }

    private fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun weight() =
        LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

    private fun label(text: String, size: Float = 16f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

    private fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(48)
            setTextColor(Color.WHITE)
            background =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(Color.rgb(45, 70, 73)),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(PlayerScreens.surface)
                        cornerRadius = dp(16).toFloat()
                    },
                    null,
                )
            setOnClickListener { action() }
        }

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    private fun clock(ms: Long): String {
        val s = ms.coerceAtLeast(0) / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateSelectionTools() {
        (selectionButton as IconButton).icon =
            if (selecting) IconButton.Kind.DONE else IconButton.Kind.SELECT
        selectionButton.contentDescription = if (selecting) "Завершить выбор" else "Выбрать треки"
        bulkButton.visibility = if (selecting) View.VISIBLE else View.GONE
        bulkButton.text = "В плейлист (${selection.size})"
        bulkButton.isEnabled = selection.isNotEmpty()
        bulkButton.alpha = if (bulkButton.isEnabled) 1f else 0.45f
        moreTools.visibility = if (selecting) View.VISIBLE else View.GONE
    }

    private fun render() {
        if (!::list.isInitialized) return
        updateSelectionTools()
        val scrollView = list.parent as? ScrollView
        val scrollY = scrollView?.scrollY ?: 0
        list.removeAllViews()
        trackLabels.clear()
        val allowed = selectedPlaylist?.let { library.playlist(it) }
        val ordered =
            library.ordered(tracks, selectedPlaylist.orEmpty(), prefs.getInt("librarySort", 0))
        visible =
            ordered.filter {
                (allowed == null || it.uri in allowed) &&
                    (it.title.contains(query, true) || it.artist.contains(query, true))
            }
        browserTitle.text = selectedPlaylist ?: "Библиотека"
        browserCount.text = if (scanning) "Проверяем папки…" else "${visible.size} песен"
        if (visible.isEmpty())
            list.addView(
                label(
                    if (library.roots().isEmpty())
                        "Добавь папку через меню ⋯.\nВ библиотеку попадут песни от 30 секунд."
                    else "Песен не найдено. Обнови библиотеку через меню ⋯.",
                    16f,
                )
            )
        val favorites = library.playlist("Избранное")
        if (selecting)
            list.addView(
                button("Выбрать все показанные (${selection.size} выбрано)") {
                    selection.addAll(visible.map { it.uri })
                    render()
                }
            )
        visible.forEach { track ->
            val r =
                row().apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(PlayerScreens.bg)
                            cornerRadius = dp(14).toFloat()
                        }
                }
            if (selecting)
                r.addView(
                    CheckBox(this).apply {
                        isChecked = track.uri in selection
                        setOnCheckedChangeListener { _, yes ->
                            if (yes) selection.add(track.uri) else selection.remove(track.uri)
                            updateSelectionTools()
                        }
                    }
                )
            val number =
                if (selectedPlaylist != null)
                    (track.trackNumber ?: filenameTrackNumber(track))
                        ?.let { "№%02d · ".format(it) }
                        .orEmpty()
                else ""
            val details = mutableListOf(track.artist.ifBlank { track.folder })
            if (selectedPlaylist != null) {
                details.add(track.albumName())
                track.year?.let { details.add(it.toString()) }
                track.discNumber?.let { details.add("диск $it") }
            }
            details.add(clock(track.duration))
            val caption = "$number${track.title}\n${details.joinToString(" · ")}"
            val styled =
                android.text.SpannableString(caption).apply {
                    val start = caption.indexOf('\n') + 1
                    setSpan(
                        android.text.style.RelativeSizeSpan(0.76f),
                        start,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                    setSpan(
                        android.text.style.ForegroundColorSpan(PlayerScreens.muted),
                        start,
                        length,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
            val info = label(caption, 18f).apply { text = styled }
            trackLabels[track.uri] = info
            info.setOnClickListener {
                if (selecting) {
                    if (!selection.add(track.uri)) selection.remove(track.uri)
                    render()
                } else startTrack(track)
            }
            info.setOnLongClickListener {
                trackMenu(track)
                true
            }
            r.addView(info, weight())
            val heart =
                button(if (track.uri in favorites) "♥" else "♡") {
                    library.toggleFavorite(track)
                    render()
                }
            heart.contentDescription = "Избранное: ${track.title}"
            heart.setOnLongClickListener {
                addToPlaylists(track)
                true
            }
            r.addView(heart, LinearLayout.LayoutParams(dp(48), dp(52)))
            list.addView(
                r,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) },
            )
        }
        highlightTrack(true)
        scrollView?.post { if (alive) scrollView.scrollTo(0, scrollY) }
    }

    private fun startTrack(track: Track) {
        val c = controller ?: return toast("Плеер ещё подключается")
        prefs.edit().putBoolean("onlinePlayerOrigin",false).apply()
        sendPlaybackCommand(
            c,
            PlaybackCommands.START_QUEUE,
            Bundle().apply {
                putString("uri", track.uri)
                putString("source", selectedPlaylist.orEmpty())
            },
        )
    }

    private fun sendPlaybackCommand(c: MediaController, action: String, args: Bundle) {
        val request = c.sendCustomCommand(SessionCommand(action, Bundle.EMPTY), args)
        request.addListener(
            {
                if (alive)
                    runCatching { request.get() }
                        .onSuccess {
                            if (it.resultCode != SessionResult.RESULT_SUCCESS)
                                toast("Не удалось применить действие плеера")
                            updatePlayer()
                        }
                        .onFailure { toast("Не удалось применить действие плеера") }
            },
            java.util.concurrent.Executor { handler.post(it) },
        )
    }

    private fun repeatDialog() {
        val c = controller ?: return toast("Плеер ещё подключается")
        val modes =
            intArrayOf(Player.REPEAT_MODE_OFF, Player.REPEAT_MODE_ALL, Player.REPEAT_MODE_ONE)
        AlertDialog.Builder(this)
            .setTitle("Повтор")
            .setSingleChoiceItems(
                arrayOf(
                    "Без повтора — остановиться в конце списка",
                    "Повтор всего списка",
                    "Повтор одной песни",
                ),
                modes.indexOf(c.repeatMode),
            ) { dialog, index ->
                sendPlaybackCommand(
                    c,
                    PlaybackCommands.SET_REPEAT,
                    Bundle().apply { putInt("mode", modes[index]) },
                )
                dialog.dismiss()
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun restorePlayingSource() {
        if (prefs.getString("queue", "[]") == "[]" && (controller?.mediaItemCount ?: 0) == 0) return
        // Browsing another list does not change the source of the playing queue.
        val source = prefs.getString("queueSource", "").orEmpty()
        selectedPlaylist =
            source.takeIf {
                it.isNotEmpty() && (it == "Избранное" || it in library.playlistNames())
            }
        selecting = false
        selection.clear()
        query = ""
        search.setText("")
        render()
    }

    private fun updatePlayer() {
        if (!screenStarted) return
        val c = controller ?: return
        highlightTrack(false)
        now.text = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Выбери песню"
        play.text = if (c.isPlaying) "❚❚" else "▶"
        val d = c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
            ?: c.currentMediaItem?.mediaMetadata?.extras?.getLong("onlineDuration") ?: 0L
        position.text =
            getString(
                dev.alex.folderplayer.R.string.duration_format,
                clock(c.currentPosition),
                clock(d),
            )
        onlineSection.updateNowPlaying(now.text.toString(),position.text.toString())
        if (!dragging)
            seek.progress =
                if (d > 0) (c.currentPosition * 1000 / d).toInt().coerceIn(0, 1000) else 0
        val track = currentTrack()
        val id = c.currentMediaItem?.mediaId
        if (directId != null && directId != id) stopDirectLyrics(true)
        screens.update(
            PlayerScreens.State(
                id,
                now.text.toString(),
                track?.artist ?: c.mediaMetadata.artist?.toString().orEmpty(),
                listOfNotNull(track?.album?.takeIf { it.isNotBlank() }, track?.year?.toString())
                    .joinToString(" · "),
                prefs.getString("queueSource", "").orEmpty().ifBlank { "Библиотека" },
                c.isPlaying,
                if(id?.startsWith("afp-yandex://")==true) OnlineLibraryActions(this).contains("favorites",Uri.parse(id).lastPathSegment.orEmpty())
                else id != null && id in library.playlist("Избранное"),
                c.repeatMode,
                c.currentPosition,
                d,
                prefs.getInt("seekSeconds", 10),
            )
        )
        if (page == "queue" && (queueTimeline !== c.currentTimeline || queueTracks !== tracks)) {
            queueTimeline = c.currentTimeline
            queueTracks = tracks
            val byId = tracks.associateBy { it.uri }
            val entries =
                (0 until c.mediaItemCount).map { i ->
                    val item = c.getMediaItemAt(i)
                    val local = byId[item.mediaId]
                    PlayerScreens.Entry(
                        item.mediaId,
                        local?.title ?: item.mediaMetadata.title?.toString().orEmpty(),
                        local?.artist ?: item.mediaMetadata.artist?.toString().orEmpty(),
                        local?.duration ?: item.mediaMetadata.extras?.getLong("onlineDuration") ?: 0L,
                    )
                }
            screens.setQueue(entries)
        }
        if (page != "library" && track != null && mediaLoadedId != id) loadScreenMedia(track)
        if (page == "translation" && track != null && translationId != id) translate(track)
    }

    private fun skip(direction: Int) {
        controller?.let { c ->
            val step = prefs.getInt("seekSeconds", 10) * 1000L
            val target = (c.currentPosition + step * direction).coerceAtLeast(0)
            c.seekTo(if (c.duration > 0) target.coerceAtMost(c.duration) else target)
        }
    }

    private fun trackMenu(track: Track) {
        val options = mutableListOf("Добавить в плейлист", "В конец очереди", "Сведения")
        if (selectedPlaylist != null)
            options.addAll(listOf("Убрать из этого плейлиста", "Выше", "Ниже"))
        options.add("Обложка и текст")
        options.add("Год альбома")
        AlertDialog.Builder(this)
            .setTitle(track.title)
            .setItems(options.toTypedArray()) { _, which ->
                if (which == options.lastIndex)
                    albumYears.show(tracks.filter { it.albumIdentity() == track.albumIdentity() })
                else
                    when (which) {
                        0 -> addToPlaylists(track)
                        1 -> controller?.addMediaItem(track.item())
                        2 ->
                            AlertDialog.Builder(this)
                                .setTitle(track.title)
                                .setMessage(
                                    "${track.artist}\nФайл: ${track.filename.ifBlank { androidx.documentfile.provider.DocumentFile.fromSingleUri(this,Uri.parse(track.uri))?.name ?: "Неизвестно" }}\nПапка: ${track.folder}\n${clock(track.duration)}"
                                )
                                .setPositiveButton("Закрыть", null)
                                .show()
                        3 ->
                            if (selectedPlaylist == null) manageMedia(track)
                            else
                                selectedPlaylist?.let { name ->
                                    library.removeTrack(name, track.uri)?.let { removed ->
                                        render()
                                        AlertDialog.Builder(this)
                                            .setMessage(
                                                "Песня удалена из плейлиста. Файл сохранён."
                                            )
                                            .setPositiveButton("Вернуть") { _, _ ->
                                                if (library.restoreTrack(removed)) render()
                                                else toast("Плейлист уже удалён")
                                            }
                                            .setNegativeButton("Закрыть", null)
                                            .show()
                                    }
                                }
                        4 -> moveTrack(track, -1)
                        5 -> moveTrack(track, 1)
                        else -> manageMedia(track)
                    }
            }
            .show()
    }

    private fun moveTrack(track: Track, delta: Int) {
        val name = selectedPlaylist ?: return
        val saved = library.playlist(name)
        val items =
            (library.ordered(tracks, name, prefs.getInt("librarySort", 0)).map { it.uri } +
                    saved.filter { id -> tracks.none { it.uri == id } })
                .toMutableList()
        val from = items.indexOf(track.uri)
        val to = from + delta
        if (from < 0 || to !in items.indices) return
        java.util.Collections.swap(items, from, to)
        library.savePlaylist(name, items)
        library.setPlaylistSort(name, SORT_MANUAL)
        library.clearRandom(name)
        reorderPlayingQueue(name)
        render()
    }

    private fun sortDialog() {
        selectedPlaylist?.let { name ->
            AlertDialog.Builder(this)
                .setTitle("Порядок «$name»")
                .setSingleChoiceItems(
                    arrayOf(
                        "Вручную",
                        "По названию — 1, 2, … 10",
                        "По имени файла — 1, 2, … 10",
                        "По альбомам: год → диск → трек",
                        "Случайный порядок",
                    ),
                    if (library.isRandom(name)) 4 else library.playlistSort(name),
                ) { d, mode ->
                    controller?.shuffleModeEnabled = false
                    if (mode == 4) library.randomize(tracks, name, prefs.getInt("librarySort", 0))
                    else {
                        library.clearRandom(name)
                        library.setPlaylistSort(name, mode)
                    }
                    render()
                    reorderPlayingQueue(name)
                    d.dismiss()
                    if (mode == SORT_ALBUM) {
                        val source = tracks.filter { it.uri in library.playlist(name) }
                        val count = albumGroups(source).count { it.year == null }
                        if (count > 0)
                            AlertDialog.Builder(this)
                                .setTitle("Нет года у $count альбомов")
                                .setMessage(
                                    "Можно найти недостающие годы в MusicBrainz и сохранить для офлайн-сортировки."
                                )
                                .setPositiveButton("Найти годы") { _, _ ->
                                    albumYears.show(source, true)
                                }
                                .setNegativeButton("Позже", null)
                                .show()
                    }
                }
                .setNeutralButton("Годы альбомов") { _, _ ->
                    albumYears.show(tracks.filter { it.uri in library.playlist(name) })
                }
                .setNegativeButton("Отмена", null)
                .show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Сортировка библиотеки")
            .setSingleChoiceItems(
                arrayOf("Название", "Исполнитель", "Папка и имя файла", "Случайный порядок"),
                if (library.isRandom("")) 3 else prefs.getInt("librarySort", 0),
            ) { d, i ->
                controller?.shuffleModeEnabled = false
                if (i == 3) library.randomize(tracks, "", prefs.getInt("librarySort", 0))
                else {
                    library.clearRandom("")
                    prefs.edit().putInt("librarySort", i).apply()
                }
                render()
                reorderPlayingQueue("")
                d.dismiss()
            }
            .setNeutralButton("Годы альбомов") { _, _ -> albumYears.show(tracks) }
            .show()
    }

    private fun reorderPlayingQueue(name: String, refreshSource: Boolean = false) {
        controller?.let {
            sendPlaybackCommand(
                it,
                PlaybackCommands.REORDER_QUEUE,
                Bundle().apply {
                    putString("source", name)
                    putBoolean("refreshSource", refreshSource)
                },
            )
        }
    }

    private fun addFolderToPlaylist(name: String, onDone: () -> Unit = {}) {
        val folders = musicFolders(tracks)
        if (folders.isEmpty()) {
            toast("Добавь папку в библиотеку через ⋯ и дождись проверки")
            onDone()
            return
        }
        val duplicatePaths = folders.groupingBy { it.path }.eachCount()
        val labels =
            folders.mapIndexed { index, folder ->
                val count = tracks.count { folder.contains(it, true) }
                "${folder.path} · $count песен" +
                    if ((duplicatePaths[folder.path] ?: 0) > 1) " (${index+1})" else ""
            }
        AlertDialog.Builder(this)
            .setTitle("Из папки в «$name»")
            .setItems(labels.toTypedArray()) { _, index ->
                val folder = folders[index]
                val panel =
                    LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(24, 8, 24, 8)
                    }
                val count = label("", 16f)
                val recursive =
                    CheckBox(this).apply {
                        text = "Включить подпапки"
                        isChecked = false
                    }
                val follow =
                    CheckBox(this).apply {
                        text = "Добавлять новые песни при обновлении библиотеки"
                        isChecked = name != "Избранное"
                        visibility = if (name == "Избранное") View.GONE else View.VISIBLE
                    }
                fun updateCount() {
                    count.text =
                        "Песен: ${tracks.count { folder.contains(it,recursive.isChecked) }}. Уже добавленные не дублируются."
                }
                recursive.setOnCheckedChangeListener { _, _ -> updateCount() }
                panel.addView(count)
                panel.addView(recursive)
                panel.addView(follow)
                updateCount()
                AlertDialog.Builder(this)
                    .setTitle(folder.path)
                    .setView(panel)
                    .setPositiveButton("Добавить") { _, _ ->
                        if (name != "Избранное" && name !in library.playlistNames())
                            toast("Плейлист уже удалён")
                        else {
                            val added =
                                library.addFolder(
                                    name,
                                    folder,
                                    recursive.isChecked,
                                    follow.isChecked,
                                )
                            render()
                            toast("Добавлено песен: $added")
                        }
                        onDone()
                    }
                    .setNegativeButton("Отмена") { _, _ -> onDone() }
                    .setOnCancelListener { onDone() }
                    .show()
            }
            .setNegativeButton("Отмена") { _, _ -> onDone() }
            .setOnCancelListener { onDone() }
            .show()
    }

    private fun bulkAdd() {
        if (selection.isEmpty()) {
            toast("Нажми «Выбрать» и отметь песни")
            return
        }
        val names = (listOf("Избранное") + library.playlistNames()).distinct()
        val checked = BooleanArray(names.size)
        AlertDialog.Builder(this)
            .setTitle("Добавить ${selection.size} песен")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, value ->
                checked[i] = value
            }
            .setPositiveButton("Добавить") { _, _ ->
                names.forEachIndexed { i, name ->
                    if (checked[i]) library.savePlaylist(name, library.playlist(name) + selection)
                }
                selection.clear()
                selecting = false
                render()
            }
            .setNeutralButton("Новый") { _, _ -> createPlaylist { bulkAdd() } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun renamePlaylist() {
        val old = selectedPlaylist ?: return
        val input = EditText(this).apply { setText(old) }
        val d =
            AlertDialog.Builder(this)
                .setTitle("Переименовать плейлист")
                .setView(input)
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name == old) d.dismiss()
                else if (library.renamePlaylist(old, name)) {
                    selectedPlaylist = name
                    if (prefs.getString("queueSource", "") == old)
                        prefs.edit().putString("queueSource", name).apply()
                    render()
                    d.dismiss()
                } else input.error = "Название пустое, занято или зарезервировано"
            }
        }
        d.show()
    }

    private fun createPlaylist(after: (String) -> Unit) {
        val input = EditText(this).apply { hint = "Название плейлиста" }
        val fromFolder = CheckBox(this).apply { text = "Добавить песни из папки" }
        val panel =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 8, 24, 8)
                addView(input)
                addView(fromFolder)
            }
        val dialog =
            AlertDialog.Builder(this)
                .setTitle("Новый плейлист")
                .setView(panel)
                .setPositiveButton("Создать", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) input.error = "Введи название"
                else if (name == "Избранное" || name in library.playlistNames())
                    input.error = "Такое название уже есть"
                else {
                    library.savePlaylist(name, emptyList())
                    dialog.dismiss()
                    if (fromFolder.isChecked) addFolderToPlaylist(name) { after(name) }
                    else after(name)
                }
            }
        }
        dialog.show()
    }

    private fun choosePlaylist() {
        val names = (listOf("Избранное") + library.playlistNames()).distinct()
        AlertDialog.Builder(this)
            .setTitle("Плейлисты")
            .setItems((names + "＋ Создать плейлист").toTypedArray()) { _, i ->
                if (i == names.size)
                    createPlaylist { name ->
                        selectedPlaylist = name
                        selecting = false
                        selection.clear()
                        render()
                    }
                else {
                    selectedPlaylist = names[i]
                    selecting = false
                    selection.clear()
                    render()
                }
            }
            .apply {
                if (selectedPlaylist != null)
                    setNeutralButton("Текущий плейлист") { _, _ -> playlistMenu() }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun addToPlaylists(track: Track) {
        if(track.uri.startsWith("afp-yandex://")) { toast("Смешанные сохраняемые плейлисты — следующий этап. Избранное доступно кнопкой ♥");return }
        val names = (listOf("Избранное") + library.playlistNames()).distinct()
        val checked = names.map { track.uri in library.playlist(it) }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("${track.title}: плейлисты")
            .setMultiChoiceItems(names.toTypedArray(), checked) { _, i, value ->
                checked[i] = value
            }
            .setPositiveButton("Сохранить") { _, _ ->
                names.forEachIndexed { i, name ->
                    val uris = library.playlist(name).toMutableSet()
                    if (checked[i]) uris.add(track.uri) else uris.remove(track.uri)
                    library.savePlaylist(name, uris)
                }
                render()
            }
            .setNeutralButton("Новый") { _, _ -> createPlaylist { addToPlaylists(track) } }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun playlistMenu() {
        val name = selectedPlaylist ?: return
        val options = mutableListOf("Добавить из папки", "Годы альбомов")
        if (name != "Избранное")
            options.addAll(listOf("Связанные папки", "Переименовать", "Удалить плейлист"))
        AlertDialog.Builder(this)
            .setTitle(name)
            .setItems(options.toTypedArray()) { _, i ->
                when (i) {
                    0 -> addFolderToPlaylist(name)
                    1 -> albumYears.show(tracks.filter { it.uri in library.playlist(name) })
                    2 -> linkedPlaylistFolders(name)
                    3 -> renamePlaylist()
                    4 ->
                        AlertDialog.Builder(this)
                            .setTitle("Удалить «$name»?")
                            .setMessage("Песни останутся на телефоне.")
                            .setPositiveButton("Удалить") { _, _ ->
                                library.removePlaylist(name)
                                if (prefs.getString("queueSource", "") == name)
                                    prefs.edit().putString("queueSource", "").apply()
                                selectedPlaylist = null
                                render()
                            }
                            .setNegativeButton("Отмена", null)
                            .show()
                }
            }
            .show()
    }

    private fun linkedPlaylistFolders(name: String) {
        val links = library.linkedFolders(name)
        val labels =
            links.map {
                it.folder.path + if (it.recursive) " · с подпапками" else " · без подпапок"
            } + "＋ Добавить папку"
        AlertDialog.Builder(this)
            .setTitle("Автопополнение «$name»")
            .setItems(labels.toTypedArray()) { _, i ->
                if (i == links.size) addFolderToPlaylist(name)
                else {
                    val link = links[i]
                    val recursive =
                        CheckBox(this).apply {
                            text = "Включить подпапки"
                            isChecked = link.recursive
                        }
                    AlertDialog.Builder(this)
                        .setTitle(link.folder.path)
                        .setView(recursive)
                        .setPositiveButton("Сохранить") { _, _ ->
                            library.configureFolder(name, link.folder, recursive.isChecked)
                            library.syncFolderPlaylists(tracks)
                            render()
                            reorderPlayingQueue(name, true)
                        }
                        .setNeutralButton("Отключить") { _, _ ->
                            library.configureFolder(name, link.folder, null)
                            render()
                            toast("Автопополнение отключено. Песни оставлены.")
                        }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun recognitionMenu() {
        val track =
            currentTrack()
                ?: run {
                    toast("Сначала включи песню")
                    return
                }
        AlertDialog.Builder(this)
            .setTitle("Определить песню")
            .setItems(arrayOf("Текущий трек · по умолчанию", "Музыка рядом")) { _, i ->
                if (i == 1) identifySong()
                else
                    startActivity(
                        Intent(this, RecognitionActivity::class.java)
                            .putExtra("localUri", track.uri)
                            .putExtra("localPosition", controller?.currentPosition ?: 0L)
                            .putExtra("localDuration", track.duration)
                    )
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun identifySong() {
        controller?.pause()
        startActivity(Intent(this, RecognitionActivity::class.java))
    }

    private fun settings() {
        val names =
            mutableListOf(
                "Добавить папку",
                "Выбранные папки",
                "Обновить библиотеку",
                "Шаг перемотки",
                "Таймер сна",
                "Расширения",
                "О приложении",
                "История текстов",
            )
        optionMenu(this, "Настройки", names) { i ->
            when (i) {
                0 ->
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            ),
                        100,
                    )
                1 -> folders()
                2 -> scan()
                3 ->
                    AlertDialog.Builder(this)
                        .setTitle("Шаг перемотки")
                        .setSingleChoiceItems(
                            arrayOf("10 секунд", "20 секунд"),
                            if (prefs.getInt("seekSeconds", 10) == 10) 0 else 1,
                        ) { d, n ->
                            prefs.edit().putInt("seekSeconds", if (n == 0) 10 else 20).apply()
                            d.dismiss()
                        }
                        .show()
                4 ->
                    AlertDialog.Builder(this)
                        .setTitle("Остановить музыку через…")
                        .setItems(
                            arrayOf("15 минут", "30 минут", "60 минут", "Выключить таймер")
                        ) { _, n ->
                            prefs
                                .edit()
                                .putLong(
                                    "sleepDeadline",
                                    if (n == 3) 0
                                    else System.currentTimeMillis() + listOf(15, 30, 60)[n] * 60000L,
                                )
                                .apply()
                            toast(if (n == 3) "Таймер выключен" else "Таймер установлен")
                        }
                        .show()
                5 -> extensions()
                6 -> about()
            }
        }
    }

    private fun folders() {
        val roots = library.roots()
        if (roots.isEmpty()) return toast("Папки ещё не выбраны")
        val labels =
            roots.map {
                androidx.documentfile.provider.DocumentFile.fromTreeUri(this, Uri.parse(it))?.name
                    ?: it
            }
        AlertDialog.Builder(this)
            .setTitle("Нажми папку, чтобы убрать")
            .setItems(labels.toTypedArray()) { _, i ->
                AlertDialog.Builder(this)
                    .setTitle("Убрать «${labels[i]}»?")
                    .setMessage("Файлы останутся на телефоне.")
                    .setPositiveButton("Убрать") { _, _ ->
                        if (scanning) toast("Дождись завершения проверки")
                        else {
                            library.setRoots(roots.filterIndexed { n, _ -> n != i })
                            runCatching {
                                contentResolver.releasePersistableUriPermission(
                                    Uri.parse(roots[i]),
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                            }
                            controller?.pause()
                            controller?.clearMediaItems()
                            scan()
                        }
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun scan() {
        LocalArtwork.clear()
        if (scanning) return
        scanning = true
        render()
        executor.execute {
            val result = runCatching { library.scan() }
            handler.post {
                if (alive) {
                    scanning = false
                    result
                        .onSuccess { (items, errors) ->
                            tracks = items
                            render()
                            reorderPlayingQueue(prefs.getString("queueSource", "").orEmpty(), true)
                            toast(
                                "Найдено ${items.size} песен" +
                                    if (errors > 0) ". Не удалось прочитать файлов/папок: $errors"
                                    else ""
                            )
                        }
                        .onFailure {
                            render()
                            toast("Не удалось обновить библиотеку")
                        }
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 107) {
            if (page == "translation") currentTrack()?.let { translate(it, true) }
            return
        }
        if (requestCode == 104 && resultCode == RESULT_OK)
            data?.data?.let { extensionManager.importEngine(it) }
        if (requestCode == 103 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                cancelTranslation()
                val track = currentTrack()
                screens.setTranslation(track?.uri, "", "Устанавливаем и проверяем языковой пакет…")
                executor.execute {
                    val result = runCatching {
                        contentResolver.openFileDescriptor(uri, "r")?.use {
                            translationClient.install(it)
                        } ?: error("Не удалось открыть пакет")
                    }
                    handler.post {
                        if (alive) {
                            result
                                .onSuccess {
                                    extensionManager.enable()
                                    toast("Офлайн-перевод готов")
                                    currentTrack()?.let { translate(it, true) }
                                }
                                .onFailure {
                                    screens.setTranslation(
                                        currentTrack()?.uri,
                                        "",
                                        it.message ?: "Не удалось установить пакет",
                                        1,
                                    )
                                }
                        }
                    }
                }
            }
        if (requestCode == 101 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                executor.execute {
                    val result = runCatching {
                        lyricStore.importText(uri).also {
                            lyricStore.save(target, it)
                            prefs.edit().remove("lyricsSource:$target").apply()
                        }
                    }
                    handler.post {
                        if (alive)
                            result
                                .onSuccess {
                                    tracks.find { it.uri == target }?.let { localMedia(it) }
                                }
                                .onFailure { toast(it.message ?: "Не удалось импортировать текст") }
                    }
                }
            }
        if (requestCode == 102 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                executor.execute {
                    val result = runCatching {
                        val text = lyricStore.read(target) ?: error("Нет сохранённого текста")
                        contentResolver.openOutputStream(uri, "wt")?.use {
                            it.write(text.toByteArray(Charsets.UTF_8))
                        } ?: error("Не удалось открыть файл")
                    }
                    handler.post {
                        if (alive)
                            toast(
                                if (result.isSuccess) "Текст экспортирован"
                                else "Не удалось экспортировать текст"
                            )
                    }
                }
            }
        if (requestCode == 100 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                runCatching {
                        contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        library.setRoots((library.roots() + uri.toString()).distinct())
                        scan()
                    }
                    .onFailure { toast("Не удалось сохранить доступ к папке") }
            }
    }

    private class MainOperations(
        val translation: RetainedOperation<SongTranslationResult>,
        val lyrics: RetainedOperation<List<LyricsSearch.Result>>,
    )

    override fun onRetainNonConfigurationInstance(): Any =
        MainOperations(translationWork, lyricsWork)

    override fun onSaveInstanceState(out: Bundle) {
        out.putString("soundReturn", soundReturn)
        out.putString("uiPage", page)
        out.putString("lyricTarget", lyricTarget)
        out.putString("libraryQuery", query)
        out.putString("selectedPlaylist", selectedPlaylist)
        out.putStringArrayList("selection", ArrayList(selection))
        out.putBoolean("selecting", selecting)
        super.onSaveInstanceState(out)
    }

    private fun currentSongMenu(track: Track) {
        stopDirectLyrics()
        SongMenu.show(this, track.title, track.uri.startsWith("afp-yandex://")) { action ->
            when (action) {
                SongMenu.Action.SHARE -> YandexTrackShare.show(this,track.uri,track.title)
                SongMenu.Action.SEARCH -> {
                    showPage("lyrics")
                    directLyrics(track)
                }
                SongMenu.Action.IMPORT -> {
                    lyricTarget = track.uri
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE),
                        101,
                    )
                }
                SongMenu.Action.PLAYLIST -> addToPlaylists(track)
                SongMenu.Action.YEAR ->
                    albumYears.show(tracks.filter { it.albumIdentity() == track.albumIdentity() })
                SongMenu.Action.SEARCH_NAME -> editSearchName(track)
                SongMenu.Action.LANGUAGE -> editSongLanguage(track)
                SongMenu.Action.RETRANSLATE -> {
                    showPage("translation")
                    renewTranslation(track)
                }
                else ->
                    executor.execute {
                        val raw = runCatching { loadLyrics(track) }.getOrNull().orEmpty()
                        handler.post {
                            if (alive)
                                when (action) {
                                    SongMenu.Action.EDIT -> editLyrics(track, raw)
                                    SongMenu.Action.TIMING -> markLyrics(track, raw)
                                    SongMenu.Action.EXPORT ->
                                        if (raw.isBlank()) toast("Сначала добавь текст")
                                        else {
                                            lyricTarget = track.uri
                                            val timed = LyricsDocument(raw).cues.isNotEmpty()
                                            startActivityForResult(
                                                Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                    .setType("text/plain")
                                                    .addCategory(Intent.CATEGORY_OPENABLE)
                                                    .putExtra(
                                                        Intent.EXTRA_TITLE,
                                                        track.title.replace(
                                                            Regex("[\\/:*?\"<>|]"),
                                                            "_",
                                                        ) + if (timed) ".lrc" else ".txt",
                                                    ),
                                                102,
                                            )
                                        }
                                    else -> Unit
                                }
                        }
                    }
            }
        }
    }

    private val artworkWorker=java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun currentTrack(): Track? {
        val item=controller?.currentMediaItem ?: return null
        tracks.find { it.uri==item.mediaId }?.let { return it }
        if(OnlinePlayback.trackId(Uri.parse(item.mediaId))==null)return null
        val meta=item.mediaMetadata
        return Track(item.mediaId,meta.title?.toString().orEmpty(),meta.artist?.toString().orEmpty(),
            meta.extras?.getLong("onlineDuration") ?: 0L,"Яндекс Музыка")
    }

    private fun backPage() {
        val target =
            when (page) {
                "player" -> "library"
                "sound" -> soundReturn
                "translation" -> "lyrics"
                else -> "player"
            }
        if (page == "player") {
            showPage(target)
            if(prefs.getBoolean("onlinePlayerOrigin",false))onlineSection.show()
        }
        else pageMotion.leavePage { if (alive) showPage(target) }
    }

    private fun previewPlayer(travel: Float) {
        if (page != "library") return
        if (screens.view.visibility != View.VISIBLE) {
            screens.show("player")
            updatePlayer()
            currentTrack()?.let { loadScreenMedia(it) }
        }
        pageMotion.preview(root.height, travel)
    }

    private fun showPage(value: String, animate: Boolean = true) {
        val before = page
        page =
            value.takeIf { it in setOf("player", "lyrics", "translation", "queue", "sound") }
                ?: "library"
        if (page != "translation") cancelTranslation()
        refreshTicker()
        if (page != "lyrics") {
            stopDirectLyrics()
            lyricsRequestedId = null
        }
        prefs.edit().putString("uiPage", page).apply()
        layout.visibility = View.VISIBLE
        layout.importantForAccessibility =
            if (page == "library") View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
            else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        val revealStart =
            if (before == "library" && screens.view.visibility == View.VISIBLE)
                screens.view.translationY
            else null
        if (page != "library") {
            if (page == "queue") queueTimeline = null
            screens.show(page)
            updatePlayer()
        }
        pageMotion.show(
            page != "library",
            root.height,
            animate && before != page,
            before == "library",
            revealStart,
        )
        if (page == "translation") currentTrack()?.let { translate(it) }
    }

    private fun openLyrics() {
        val track = currentTrack() ?: return toast("Сначала включи песню")
        lyricsRequestedId = track.uri
        mediaLoadedId = null
        showPage("lyrics")
    }

    @Deprecated("Framework back compatibility")
    override fun onBackPressed() {
        if (page != "library") backPage() else super.onBackPressed()
    }

    private fun loadScreenMedia(track: Track) {
        mediaLoadedId = track.uri
        val generation = ++mediaGeneration
        screens.setArtwork(track.uri, null)
        screens.lyricsSearching(track.uri, false, "Читаем локальный текст…")
        // Capture artwork from this queue item, never the aggregated (possibly previous) metadata.
        val cover=controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString().orEmpty()
        executor.execute {
            val bitmap = runCatching {
                if(track.uri.startsWith("afp-yandex://")) OnlineArtwork(applicationContext).load(cover)
                else LocalArtwork.read(this, track.uri)
            }.getOrNull()
            val lyrics = runCatching { loadLyrics(track)?.let { LyricsDocument(it) } }
            handler.post {
                if (
                    alive &&
                        generation == mediaGeneration &&
                        controller?.currentMediaItem?.mediaId == track.uri
                ) {
                    screens.setArtwork(track.uri, bitmap)
                    screens.setLyrics(
                        track.uri,
                        lyrics.getOrNull(),
                        if (lyrics.isFailure)
                            "Не удалось прочитать локальный текст. Можно найти слова или добавить свой через ⋮."
                        else null,
                    )
                    if (page == "lyrics" && lyricsWork.state().key == track.uri)
                        lyricsWork.attach { showLyricsSearch(it) }
                    if (page == "lyrics" && lyricsRequestedId == track.uri) {
                        lyricsRequestedId = null
                        if (lyrics.getOrNull() == null) directLyrics(track, false)
                    }
                }
            }
        }
    }

    private fun cancelTranslation() {
        translationWork.cancel()
        translationId = null
    }

    private var translationForeground = false

    private fun translate(track: Track, force: Boolean = false, renew: Boolean = false) {
        if (!force && translationId == track.uri) return
        translationId = track.uri
        if (!force && translationWork.state().key == track.uri) {
            translationWork.attach { showTranslation(it) }
            return
        }
        val repository = SongTranslationRepository(applicationContext)
        translationWork.start(track.uri) { cancel, progress ->
            repository.translate(track, renew, cancel, progress)
        }
        translationWork.attach { showTranslation(it) }
    }

    private fun showTranslation(state: RetainedOperation.Snapshot<SongTranslationResult>) {
        val track = currentTrack() ?: return
        if (!alive || page != "translation" || state.key != track.uri) return
        if (state.busy) {
            screens.setTranslationProgress(track.uri, state.message)
            return
        }
        state.value?.let { result ->
            val message =
                if (result.incomplete > 0)
                    "Не удалось перевести фрагментов: ${result.incomplete}. Они отмечены в тексте; готовая часть сохранена на экране."
                else if (result.model == SongLanguage.RUSSIAN) "Оригинал уже на русском"
                else "Сохранено · " + TranslationClient.MODELS[result.model].orEmpty()
            screens.setTranslation(
                track.uri,
                result.text,
                message,
                if (result.incomplete > 0) 2 else 0,
            )
            return
        }
        state.failure?.let { failure ->
            if (failure is ChooseSongLanguage) {
                screens.setTranslation(
                    track.uri,
                    "",
                    "Не удалось уверенно определить язык. Выбери язык этой песни.",
                    2,
                )
                if (translationForeground) chooseSongLanguage(track, failure.source, failure.models)
            } else
                screens.setTranslationProgress(
                    track.uri,
                    failure.message ?: "Не удалось перевести",
                    2,
                )
        }
    }

    private fun renewTranslation(track: Track) {
        AlertDialog.Builder(this)
            .setTitle("Перевести заново?")
            .setMessage(
                "Сохранённый перевод этой песни будет заменён только после успешного завершения. Остальные переводы останутся."
            )
            .setPositiveButton("Перевести") { _, _ -> translate(track, true, true) }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun chooseSongLanguage(
        track: Track,
        source: String,
        available: List<Pair<String, String>>,
    ) {
        val models = TranslationLanguages.choices(available)
        if (models.isEmpty()) {
            screens.setTranslation(
                track.uri,
                "",
                "Нет установленных языковых пакетов. Открой расширения.",
                1,
            )
            return
        }
        val choices = models.map { it.second } + "Определять автоматически"
        AlertDialog.Builder(this)
            .setTitle("Язык оригинала этой песни")
            .setItems(choices.toTypedArray()) { _, i ->
                if (currentTrack()?.uri != track.uri) return@setItems
                runCatching {
                        val saved = SongTranslationChoice(this)
                        if (i == models.size) saved.clear(track.uri, source)
                        else saved.save(track.uri, source, models[i].first)
                    }
                    .onSuccess { translate(track, true) }
                    .onFailure { toast("Не удалось сохранить выбор языка") }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun editSongLanguage(track: Track) {
        executor.execute {
            val result = runCatching {
                val source =
                    loadLyrics(track)?.let { LyricsDocument(it).plain }
                        ?: error("Сначала добавь текст")
                source to translationClient.availableModels().toList()
            }
            handler.post {
                if (alive && currentTrack()?.uri == track.uri)
                    result
                        .onSuccess { (source, models) ->
                            showPage("translation")
                            cancelTranslation()
                            translationId = track.uri
                            chooseSongLanguage(track, source, models)
                        }
                        .onFailure { toast(it.message ?: "Не удалось проверить языки") }
            }
        }
    }

    private fun stopDirectLyrics(clear: Boolean = false) {
        val busy = lyricsWork.state().busy
        if (busy && !clear)
            directId?.let { screens.lyricsSearching(it, false, "Поиск отменён. Можно повторить.") }
        lyricsWork.cancel()
        if (clear || busy) {
            directId = null
            directResults = emptyList()
        }
    }

    private fun editSearchName(track: Track) {
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
                .setNegativeButton("Отмена", null)
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

    private fun directLyrics(track: Track, force: Boolean = true) {
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

    private fun showLyricsSearch(state: RetainedOperation.Snapshot<List<LyricsSearch.Result>>) {
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

    private fun saveDirectLyrics(track: Track, item: LyricsSearch.Result) {
        screens.lyricsSearching(track.uri, true, "Сохраняем текст…")
        executor.execute {
            val result = runCatching {
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
            }
            handler.post {
                if (alive && page == "lyrics" && currentTrack()?.uri == track.uri) {
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

    private fun loadLyrics(track: Track): String? = SongContent(this).load(track)

    private fun localMedia(track: Track) {
        if (page != "library" && track.uri == controller?.currentMediaItem?.mediaId) {
            mediaLoadedId = null
            showPage("lyrics")
            return
        }
        manageMedia(track)
    }

    private fun manageMedia(track: Track) {
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
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE),
                        101,
                    )
                }
                .setPositiveButton("Закрыть", null)
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
            lyricTarget = track.uri
            val timed = LyricsDocument(raw).cues.isNotEmpty()
            startActivityForResult(
                Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType("text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(
                        Intent.EXTRA_TITLE,
                        track.title.replace(Regex("[\\/:*?\"<>|]"), "_") +
                            if (timed) ".lrc" else ".txt",
                    ),
                102,
            )
        }
        dialog.setOnDismissListener { lyricUpdate = null }
        dialog.show()
        executor.execute {
            var picture: android.graphics.Bitmap? = null
            runCatching {
                val retriever = android.media.MediaMetadataRetriever()
                try {
                    retriever.setDataSource(this, Uri.parse(track.uri))
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
            handler.post {
                if (alive && dialog.isShowing) {
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
                                                    Color.rgb(103, 232, 193)
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

    private fun editLyrics(track: Track, initial: String) {
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
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
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
                        executor.execute {
                            val result = runCatching {
                                lyricStore.save(track.uri, value)
                                prefs.edit().remove("lyricsSource:${track.uri}").apply()
                            }
                            handler.post {
                                if (alive) {
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
            }
        }
        dialog.show()
    }

    private fun markLyrics(track: Track, raw: String) {
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
            button("Пауза / ▶") { controller?.let { if (it.isPlaying) it.pause() else { if(it.playbackState==Player.STATE_IDLE)it.prepare();it.play() } } },
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
                .setNegativeButton("Отмена", null)
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
                    executor.execute {
                        val result = runCatching {
                            lyricStore.save(track.uri, value)
                            prefs.edit().remove("lyricsSource:${track.uri}").apply()
                        }
                        handler.post {
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
        }
        dialog.show()
    }

    private fun dialogSearch(track: Track) {
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
                .setNegativeButton("Закрыть", null)
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
        AlertDialog.Builder(this)
            .setTitle("Выбери запись · ${clock(track.duration)} на телефоне")
            .setItems(labels.toTypedArray()) { _, i -> previewLyrics(track, found[i]) }
            .setNeutralButton("Другой запрос") { _, _ -> dialogSearch(track) }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun previewLyrics(track: Track, item: LyricsSearch.Result) {
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
                .setPositiveButton("Сохранить", null)
                .setNegativeButton("Отмена", null)
                .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = item.text.isNotBlank()
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                executor.execute {
                    val result = runCatching {
                        lyricStore.save(track.uri, item.text)
                        prefs
                            .edit()
                            .putString(
                                "lyricsSource:${track.uri}",
                                "Источник: LRCLIB · ${item.title} — ${item.artist}",
                            )
                            .apply()
                    }
                    handler.post {
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
        }
        dialog.show()
    }

    private fun equalizer() {
        if (page != "sound") soundReturn = page
        showPage("sound")
    }

    private fun highlightTrack(force: Boolean) {
        val currentId = controller?.currentMediaItem?.mediaId
        if (!force && currentId == highlightedId) return
        highlightedId = currentId
        trackLabels.forEach { (uri, label) ->
            val current = uri == currentId
            label.setTextColor(if (current) Color.rgb(103, 232, 193) else Color.WHITE)
            label.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            label.isSelected = current
            (label.parent as? View)?.background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (current) Color.rgb(20, 52, 53) else PlayerScreens.bg)
                    cornerRadius = dp(14).toFloat()
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            LocalArtwork.clear()
    }

    override fun onResume() {
        super.onResume()
        translationForeground = true
        extensionManager.resume()
        appUpdates.resume()
        onlineSection.resume()
    }

    private val extensionManager: ExtensionManager by lazy {
        ExtensionManager(
            this,
            translationClient,
            { if (alive) toast(it) },
            {
                startActivityForResult(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    103,
                )
            },
            { if (alive && page == "translation") currentTrack()?.let { translate(it, true) } },
            online = { onlineSection.show() },
            updates = { appUpdates.check() },
        )
    }

    private fun extensions() {
        extensionManager.show()
    }

    private val onlineSection: OnlineSection by lazy { OnlineSection(this, { raw,index,mode,done ->
        val c=controller
        if(c==null)done(if(mode=="remove")null else "Плеер ещё подключается. Повтори запуск")
        else {
            val request=c.sendCustomCommand(SessionCommand(PlaybackCommands.START_ONLINE_QUEUE,Bundle.EMPTY),Bundle().apply {
                putString("queueId",raw);putInt("index",index);putString("mode",mode)
            })
            request.addListener({
                if(alive) {
                    val error=runCatching { request.get().resultCode }.fold(
                        { if(it==SessionResult.RESULT_SUCCESS)null else "Плеер отклонил очередь. Повтори действие" },
                        { "Не удалось передать очередь плееру. Повтори действие" })
                    done(error)
                    if(error==null && mode=="play") {
                        prefs.edit().putBoolean("onlinePlayerOrigin",true).apply()
                        showPage("player")
                    }
                    updatePlayer()
                }
            },java.util.concurrent.Executor { handler.post(it) })
        }
    },openPlayer={ prefs.edit().putBoolean("onlinePlayerOrigin",true).apply();showPage("player") }) }

    private val appUpdates: AppUpdates by lazy { AppUpdates(this, extensionManager) }

    private fun about() {
        AlertDialog.Builder(this)
            .setTitle("aFolderPlayer · ${appUpdates.installedVersion()}")
            .setMessage(
                "Музыка по папкам, тексты песен и перевод. Без рекламы и аналитики.\n\nИнтернет используется только по твоему запросу. Перевод работает на телефоне после установки языкового пакета."
            )
            .setPositiveButton("Закрыть", null)
            .setNeutralButton("Проверить обновления") { _, _ -> appUpdates.check() }
            .setNegativeButton("Подробнее") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Сервисы и лицензии")
                    .setMessage(
                        "Поиск текстов: LRCLIB. Годы альбомов: MusicBrainz. Обновления: GitHub. Распознавание: неофициальный интерфейс Shazam; отправляется звуковой отпечаток, запись и геолокация не отправляются.\n\nMedia3 / AndroidX: Apache 2.0. Языковые пакеты: см. лицензии пакетов. ONNX Runtime: MIT. Алгоритм распознавания адаптирован из ShazamIO 0.4.0.0, MIT, © 2021 dotX12.\n\nРазработка при участии ChatGPT."
                    )
                    .setPositiveButton("Закрыть", null)
                    .show()
            }
            .show()
    }

    override fun onStart() {
        super.onStart()
        screenStarted = true
        restorePlayingSource()
        refreshTicker()
    }

    override fun onStop() {
        translationForeground = false
        screenStarted = false
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        pageMotion.cancel()
        translationWork.detach()
        if (!isChangingConfigurations) translationWork.close()
        extensionManager.close()
        appUpdates.close()
        onlineSection.close()
        artworkWorker.shutdownNow()
        lyricsWork.detach()
        if (!isChangingConfigurations) lyricsWork.close()
        albumYears.close()
        alive = false
        networkCancel?.cancel()
        networkTask?.cancel(true)
        networkExecutor.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        executor.shutdown()
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
