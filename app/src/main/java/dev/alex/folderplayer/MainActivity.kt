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
    …24912 tokens truncated…d)return@submit
            val cached=if(op in listOf("collectionPage","playlistPage"))store.read(key) else null
            main.post { if(!closed && token==generation) { busy=false;if(cached!=null)apply(cached) else fetch(0,false) } }
        }
    }
    private fun checkStatus() {
        val token=generation;busy=true;status.text="Проверяем сохранённый вход…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching { extension.request("status").also { store.setSession(it.optString("sessionKey")) } }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { if(it.optBoolean("connected"))choose("collectionPage","","Мне нравится в Яндексе") else { status.text="Войди, чтобы открыть коллекцию";update() } }
                    .onFailure { fail(it) }
            } }
        }
    }
    private fun fetch(offset:Int,refresh:Boolean) {
        if(busy)return
        val token=generation;val op=operation;val id=sourceId;val key=cacheKey();val prior=JSONArray(rows.toString())
        busy=true;retry.visibility=View.GONE;status.text="Загрузка…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching {
                val value=if(op in listOf("collectionPage","playlistPage"))loader.page(op,id,offset,refresh) else extension.request(op,if(op=="search")id else "")
                if(offset>0)value.put("tracks",merge(prior,value.getJSONArray("tracks")))
                check(!closed && token==generation && !Thread.currentThread().isInterrupted) { "Загрузка отменена" }
                if(op in listOf("collectionPage","playlistPage"))store.write(key,value)
                value
            }
            main.post { if(!closed && token==generation) { busy=false;result.onSuccess { apply(it,offset>0) }.onFailure { fail(it) } } }
        }
    }
    private fun apply(value:JSONObject,append:Boolean=false) {
        data=value;playlistMode=value.has("playlists");rows=value.optJSONArray(if(playlistMode)"playlists" else "tracks") ?: JSONArray()
        if(!append) { selected.clear();selecting=false };retry.visibility=View.GONE;render();if(!append)list.setSelection(0)
    }
    private fun render() {
        if(!::title.isInitialized || !::list.isInitialized)return
        title.text=if(operation=="playlistPage")sourceTitle else "Яндекс Музыка · $sourceTitle"
        shown=OnlineRows.display(rows,query,sort,if(playlistMode)emptySet() else decisions.ids("hidden"),operation=="hidden")
        adapter.notifyDataSetChanged()
        if(!busy)status.text=(if(playlistMode)"Плейлистов: ${shown.size} · нажми «Открыть»" else "Показано ${shown.size} · загружено ${rows.length()} из ${data.optInt("total",rows.length())}")+
            data.optString("warning").takeIf { it.isNotBlank() }?.let { " · Добавленные плейлисты не загружены: $it" }.orEmpty()
        update()
    }
    private fun updateSelection() { playAll.text=if(selected.isNotEmpty())"▶ Играть выбранные · ${selected.size}" else "▶ Играть список целиком";selection.text=if(selecting)"Выбрано: ${selected.size} · нажми здесь, чтобы снять выбор" else "Долгое нажатие или флажок — выбрать песни" }
    private fun update() {
        if(!::more.isInitialized)return
        buttons.forEach { it.isEnabled=!busy };sortView.isEnabled=!busy;search.isEnabled=!busy
        more.visibility=if(!playlistMode && next()!=null)View.VISIBLE else View.GONE
        playAll.visibility=if(!playlistMode && operation!="hidden" && shown.isNotEmpty())View.VISIBLE else View.GONE
        dialog?.setCancelable(!handingOff)
        cancel.visibility=if(busy && !handingOff)View.VISIBLE else View.GONE
        updateSelection()
    }
    private fun fail(error:Throwable) {
        status.text="Не удалось загрузить: "+(error.message?.take(180) ?: "неизвестная ошибка")
        retry.visibility=View.VISIBLE;update()
        // Keep one inline error and retry; repeated taps must not stack modal windows.
    }
    private fun toggle(index:Int) { if(!selected.add(index))selected.remove(index);render() }
    private fun sourceMenu() {
        val menuGeneration=generation
        AlertDialog.Builder(host).setTitle("Онлайн-библиотека").setItems(arrayOf("Избранное в плеере","Скрытые в плеере","Обновить список","Сеть и качество")) { _,i ->
            if(busy || menuGeneration!=generation || dialog?.isShowing!=true)return@setItems
            when(i) { 0->choose("favorites","","Избранное в плеере");1->choose("hidden","","Скрытые в плеере");2->if(operation in listOf("favorites","hidden"))choose(operation,"",sourceTitle) else fetch(0,true);3->OnlineSettings(host).show(host) }
        }.show()
    }
    private fun selectedRows():JSONArray=JSONArray(shown.filter { it.sourceIndex in selected }.map { it.song })
    private fun actionsMenu() {
        val menuGeneration=generation
        if(playlistMode) { status.text="Сначала открой плейлист";return }
        val has=selected.isNotEmpty()
        val labels=mutableListOf("Выбрать все показанные","Снять выбор")
        if(has)labels+=listOf("Воспроизвести выбранные","Играть выбранные далее","Выбранные в конец очереди","В избранное плеера","Убрать из избранного",if(operation=="hidden")"Вернуть в библиотеку" else "Скрыть в плеере")
        else labels+=listOf("Воспроизвести список целиком","Весь список — играть далее","Весь список — в конец очереди")
        AlertDialog.Builder(host).setTitle(if(has)"Выбрано: ${selected.size}" else "Действия со списком").setItems(labels.toTypedArray()) { _,i ->
            if(busy || menuGeneration!=generation || dialog?.isShowing!=true)return@setItems
            when(i) {
                0->{ selecting=true;selected.clear();selected+=shown.map { it.sourceIndex };render() }
                1->{ selecting=false;selected.clear();render() }
                2->prepare("play",null)
                3->prepare("next",null)
                4->prepare("append",null)
                5->changeDecision("favorites",true)
                6->changeDecision("favorites",false)
                7->changeDecision("hidden",operation!="hidden")
            }
        }.setNegativeButton("Закрыть",null).show()
    }
    private fun changeDecision(kind:String,enabled:Boolean) {
        if(busy)return
        val snapshot=selectedRows();if(snapshot.length()==0)return
        val token=generation;busy=true;update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching { decisions.set(kind,snapshot,enabled);if(kind=="hidden" && enabled)store.saveQueue(snapshot) else null }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { handle ->
                    if(handle!=null)play(handle,0,"remove") { error -> if(!closed && token==generation && error!=null)status.text=error }
                    selected.clear();selecting=false
                    if(operation in listOf("favorites","hidden"))apply(JSONObject().put("tracks",decisions.rows(operation))) else render()
                }.onFailure { fail(it) }
            } }
        }
    }
    private fun prepare(mode:String,start:Int?) {
        if(busy || playlistMode || dialog?.isShowing!=true)return
        val token=++generation;val op=operation;val id=sourceId;val key=cacheKey()
        val initial=JSONObject(data.toString());val selectionSnapshot=selected.toSet();val q=query;val sorting=sort;val label=sourceTitle
        busy=true;status.text="Подготовка очереди…";update()
        pending=worker.submit {
            if(closed || token!=generation || Thread.currentThread().isInterrupted)return@submit
            val result=runCatching {
                val full=if(selectionSnapshot.isEmpty() && op in listOf("collectionPage","playlistPage")) {
                    loader.complete(op,id,initial,{ !closed && token==generation }) { n,total -> main.post { if(!closed && token==generation)status.text="Подготовка очереди: $n из $total" } }.also { store.write(key,it) }
                } else initial
                val all=full.optJSONArray("tracks") ?: JSONArray()
                val visible=OnlineRows.display(all,q,sorting,decisions.ids("hidden"))
                    .filter { it.song.optBoolean("available",true) && (selectionSnapshot.isEmpty() || it.sourceIndex in selectionSnapshot) }
                check(visible.isNotEmpty()) { "Нет доступных нескрытых песен для воспроизведения" }
                val index=if(start==null)0 else visible.indexOfFirst { it.sourceIndex==start }.also { check(it>=0) { "Этот трек недоступен или скрыт" } }
                check(!closed && token==generation) { "Подготовка отменена" }
                Triple(store.saveQueue(JSONArray(visible.map { it.song }),label),index,full)
            }
            main.post { if(!closed && token==generation) {
                busy=false
                result.onSuccess { (handle,index,full) ->
                    data=full;rows=full.optJSONArray("tracks") ?: rows;busy=true;handingOff=true;status.text="Передаём очередь плееру…";render();cancel.visibility=View.GONE
                    play(handle,index,mode) { error ->
                        if(!closed && token==generation) {
                            busy=false;handingOff=false
                            if(error!=null)fail(IllegalStateException(error))
                            else { render();if(mode=="play")dialog?.hide() else status.text="Добавлено в очередь" }
                        }
                    }
                }.onFailure { fail(it) }
            } }
        }
    }
    private fun cancelWork() {
        if(handingOff)return
        generation++;pending?.cancel(true);pending=null;busy=false
        status.text="Загрузка отменена";update()
    }
    fun updateNowPlaying(label:String,time:String) { now?.title?.text=label;now?.time?.text=time }
    private fun openLogin() {
        returning=true;dialog?.dismiss()
        runCatching { host.startActivity(Intent().setComponent(ComponentName(onlinePackage(host),"dev.alex.afponline.YandexAccountActivity"))) }
            .onFailure { returning=false;Toast.makeText(host,"Не удалось открыть вход",Toast.LENGTH_LONG).show() }
    }
    fun resume() { if(returning && !closed) { returning=false;show() } }
    fun close() { closed=true;generation++;dialog?.dismiss();worker.shutdownNow();covers.shutdownNow();main.removeCallbacksAndMessages(null) }
    companion object {
        private fun formatDuration(ms:Long):String=if(ms<=0)"—" else "${ms/60000}:${(ms/1000%60).toString().padStart(2,'0')}"
        fun merge(old:JSONArray,more:JSONArray):JSONArray {
            val result=JSONArray();for(array in listOf(old,more))for(i in 0 until array.length())array.optJSONObject(i)?.let { result.put(it) };return result
        }
    }
}
