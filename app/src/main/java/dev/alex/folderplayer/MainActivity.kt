package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch

class MainActivity : ThemedComponentActivity() {
    private lateinit var root: FrameLayout
    private lateinit var screens: PlayerScreens
    private val documents = MainDocuments(this, ::onDocumentResult)
    private lateinit var model: MainViewModel
    private var libraryLoading = true
    private var libraryFailure: String? = null
    private var page = "library"
    private lateinit var pageMotion: SheetMotion
    private var soundReturn = "player"
    private var lyricsRequestedId: String? = null
    private lateinit var scanWork: RetainedOperation<Library.ScanResult>
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
    private lateinit var identifyButton: Button
    private lateinit var hideTracksButton: Button
    private lateinit var deleteTracksButton: Button
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
    private val lyricsSearch by lazy {
        LyricsSearch(SearchConnections(applicationContext)).also {
            it.restoreCooldown(prefs.getLong("lyricsRetryUntil", 0))
        }
    }
    private val translationClient by lazy { TranslationClient(this) }
    private lateinit var translationWork: RetainedOperation<SongTranslationResult>
    private var translationId: String? = null
    private val networkWork by lazy { ActivityWork(applicationContext) }
    private val albumYears by lazy {
        AlbumYearDialogs(
            this,
            {
                model.reload()
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
    private val background by lazy { ActivityWork(applicationContext) }
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
    private var screenStarted = false
    private var restoredBrowseState = false
    private val ticker =
        object : Runnable {
            override fun run() {
                if (!screenStarted) return
                updatePlayer()
                if (lyricsEditorDelegate.isInitialized()) lyricsEditor.tick()
                if (controller?.isPlaying == true)
                    handler.postDelayed(
                        this,
                        if (
                            page == "lyrics" ||
                                (lyricsEditorDelegate.isInitialized() && lyricsEditor.ticking)
                        )
                            500
                        else 1000,
                    )
            }
        }

    private fun refreshTicker() {
        handler.removeCallbacks(ticker)
        if (screenStarted) handler.post(ticker)
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        CrashNotes.install(this)
        restoredBrowseState = state != null
        model =
            ViewModelProvider(this, MainViewModel.factory(application))[MainViewModel::class.java]
        translationWork = model.translation
        lyricsWork = model.lyrics
        scanWork = model.scan
        soundReturn = state?.getString("soundReturn") ?: "player"
        lyricTarget = state?.getString("lyricTarget")
        library = Library(this)
        tracks = model.libraryState.value.tracks
        libraryLoading = model.libraryState.value.loading
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
                        controller?.let {
                            if (it.isPlaying) it.pause()
                            else {
                                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                                it.play()
                            }
                        }
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
                            val item = controller?.currentMediaItem
                            val online = item?.let { media -> OnlineQueueMetadata.row(media) }
                            if (online != null) {
                                val actions = OnlineLibraryActions(this@MainActivity)
                                actions.set(
                                    "favorites",
                                    org.json.JSONArray().put(online),
                                    !actions.contains("favorites", online.optString("id")),
                                )
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
                        background.submit({ ctx ->
                            track
                                ?.let { SongContent(ctx).load(it) }
                                ?.let { LyricsDocument(it).plain } ?: ""
                        }) { result ->
                            if (alive)
                                result
                                    .onSuccess {
                                        if (track != null && currentTrack()?.uri == track.uri)
                                            documents.launch(
                                                Intent(
                                                        this@MainActivity,
                                                        SongTextActivity::class.java,
                                                    )
                                                    .putExtra("source", it)
                                                    .putExtra("songTitle", track.title)
                                                    .putExtra("artist", track.artist)
                                                    .putExtra("localUri", track.uri)
                                                    .putExtra("openTranslation", true),
                                                DocumentRequest.DOCUMENT_TRANSLATION,
                                            )
                                    }
                                    .onFailure { toast(it.message ?: "Не удалось прочитать текст") }
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
        SystemBarInsets.apply(root)
        setContentView(root)
        if (android.os.Build.VERSION.SDK_INT >= 33)
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT
            ) {
                if (page != "library") backPage() else finish()
            }
        libraryPage = state?.getInt("libraryPage", 0) ?: 0
        lastBrowserSource = selectedPlaylist
        browserTitle = label("Библиотека", 24f)
        layout.addView(browserTitle)
        browserCount = label("", 13f).apply { setTextColor(PlayerScreens.muted) }
        layout.addView(browserCount)
        val sources = row()
        sources.addView(
            button("На устройстве") {
                selectedPlaylist = null
                render()
            },
            weight(),
        )
        sources.addView(button("Яндекс Музыка") { onlineSection.show() }, weight())
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
        hideTracksButton = button("👁") { toggleSelectedTracksHidden() }
        deleteTracksButton = button("Удалить") { deleteSelectedTracks() }
        moreTools.addView(hideTracksButton, weight().apply { height = dp(48) })
        moreTools.addView(deleteTracksButton, weight().apply { height = dp(48) })
        layout.addView(moreTools)
        val quick = row()
        identifyButton = button("Определить песню") { identifySong() }
        quick.addView(identifyButton, LinearLayout.LayoutParams(0, dp(48), 2f))

        layout.addView(quick)
        search =
            EditText(this).apply {
                setText(query)
                hint = "Найти песню или исполнителя"
                setTextColor(AppAppearance.textColor)
                setHintTextColor(PlayerScreens.muted)
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
                    libraryPage = 0
                    handler.removeCallbacks(renderSearch)
                    handler.postDelayed(renderSearch, 180)
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
                if (prefs.getString("activeBook", "").orEmpty().isNotEmpty())
                    startActivity(Intent(this, BooksActivity::class.java))
                else showPage("player")
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

        play =
            button("▶") {
                controller?.let {
                    if (it.isPlaying) it.pause()
                    else {
                        if (it.playbackState == Player.STATE_IDLE) it.prepare()
                        it.play()
                    }
                }
            }
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
                                        val onlineError =
                                            generateSequence(error as Throwable?) { it.cause }
                                                .take(8)
                                                .filterIsInstance<OnlinePlayback.Problem>()
                                                .firstOrNull()
                                        toast(
                                            onlineError?.message
                                                ?: "Не удалось воспроизвести трек: ${error.errorCodeName}"
                                        )
                                    }
                                }
                            )
                            if (!restoredBrowseState && !libraryLoading) restorePlayingSource()
                            updatePlayer()
                        }
                        .onFailure { toast("Не удалось подключить плеер") }
            },
            java.util.concurrent.Executor { handler.post(it) },
        )
        BrowserPane.install(
            layout,
            scroller,
            listOf(browserTitle, browserCount, sources, search),
            listOf(tools, moreTools, quick, current, seek, controls),
        )
        render()
        val libraryScroll = state?.getInt("libraryScroll", 0) ?: 0
        if (libraryScroll > 0)
            scroller.viewTreeObserver.addOnGlobalLayoutListener(
                object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        if (libraryLoading) return
                        scroller.viewTreeObserver.removeOnGlobalLayoutListener(this)
                        scroller.scrollTo(0, libraryScroll)
                    }
                }
            )
        showPage(
            state?.getString("uiPage") ?: prefs.getString("uiPage", "library").orEmpty(),
            false,
        )
        onlineSection.restore(state?.getBundle("onlineBrowser"), model.online)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.libraryState.collect { next ->
                    val firstReady = libraryLoading && !next.loading
                    tracks = next.tracks
                    libraryLoading = next.loading
                    libraryFailure = next.error
                    if (
                        firstReady &&
                            !restoredBrowseState &&
                            query.isEmpty() &&
                            !selecting &&
                            selectedPlaylist == null
                    )
                        restorePlayingSource()
                    queueTimeline = null
                    queueTracks = null
                    render()
                    updatePlayer()
                }
            }
        }
        scanWork.attach { showScan(it) }
    }

    private val ui by lazy { PlayerUi(this) }

    private fun row() = ui.row()

    private fun dp(value: Int) = ui.dp(value)

    private fun weight() = ui.weight()

    private fun label(text: String, size: Float = 16f) = ui.label(text, size)

    private fun button(text: String, action: () -> Unit) = ui.button(text, action)

    private fun toast(text: String) = ui.toast(text)

    private fun clock(ms: Long) = ui.clock(ms)

    private fun updateSelectionTools() {
        (selectionButton as IconButton).icon =
            if (selecting) IconButton.Kind.DONE else IconButton.Kind.SELECT
        selectionButton.contentDescription = if (selecting) "Завершить выбор" else "Выбрать треки"
        bulkButton.visibility = if (selecting) View.VISIBLE else View.GONE
        bulkButton.text = "В плейлист (${selection.size})"
        bulkButton.isEnabled = !libraryLoading && selection.isNotEmpty()
        bulkButton.alpha = if (bulkButton.isEnabled) 1f else 0.45f
        moreTools.visibility = if (selecting) View.VISIBLE else View.GONE
        identifyButton.visibility = if (selecting) View.GONE else View.VISIBLE
        val allHidden =
            !libraryLoading &&
                selection.isNotEmpty() &&
                selection.all { it in library.hiddenTracks() }
        hideTracksButton.text = if (allHidden) "👁 Вернуть" else "👁 Скрыть"
        hideTracksButton.contentDescription =
            if (allHidden) "Вернуть в воспроизведение" else "Не воспроизводить"
        deleteTracksButton.contentDescription = "Удалить выбранные треки из библиотеки"
        listOf(hideTracksButton, deleteTracksButton).forEach {
            it.isEnabled = !libraryLoading && selection.isNotEmpty()
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
    }

    private var libraryPage = 0
    private var lastBrowserSource: String? = null
    private val renderSearch = Runnable { if (alive) render() }

    private val browser by lazy {
        LibraryBrowser(
            this,
            list,
            browserTitle,
            browserCount,
            thumbnails,
            library,
            object : LibraryBrowser.Actions {
                override fun refresh() = render()

                override fun reload() = model.reload()

                override fun select(id: String, selected: Boolean, redraw: Boolean) {
                    if (selected) selection.add(id) else selection.remove(id)
                    if (redraw) render() else updateSelectionTools()
                }

                override fun selectAll(ids: List<String>) {
                    selection.addAll(ids)
                    render()
                }

                override fun page(index: Int) {
                    libraryPage = index
                    render()
                }

                override fun play(track: Track) = startTrack(track)

                override fun menu(track: Track) = trackMenu(track)

                override fun addToPlaylists(track: Track) = this@MainActivity.addToPlaylists(track)
            },
        )
    }

    private fun render() {
        if (!::list.isInitialized) return
        updateSelectionTools()
        if (lastBrowserSource != selectedPlaylist) {
            libraryPage = 0
            lastBrowserSource = selectedPlaylist
        }
        val rendered =
            browser.render(
                LibraryBrowser.State(
                    tracks,
                    selectedPlaylist,
                    query,
                    selecting,
                    selection.toSet(),
                    scanning,
                    libraryLoading,
                    libraryFailure,
                    libraryPage,
                    if (libraryLoading && tracks.isEmpty()) 0 else prefs.getInt("librarySort", 0),
                )
            )
        visible = rendered.visible
        libraryPage = rendered.page
        trackLabels.clear()
        trackLabels.putAll(rendered.labels)
        highlightTrack(true)
    }

    private fun removeUnavailableFromQueue() {
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
        val playable = library.playableTracks().map { it.uri }.toSet()
        val onlineHidden = OnlineLibraryActions(this).ids("hidden")
        controller?.let { c ->
            for (index in c.mediaItemCount - 1 downTo 0) {
                if (
                    !QueueEligibility.playable(
                        c.getMediaItemAt(index).mediaId,
                        playable,
                        onlineHidden,
                    )
                )
                    c.removeMediaItem(index)
            }
        }
    }

    private fun toggleSelectedTracksHidden() {
        if (selection.isEmpty()) return
        val hide = !selection.all { it in library.hiddenTracks() }
        library.setTracksHidden(selection.toSet(), hide)
        removeUnavailableFromQueue()
        render()
        toast(
            if (hide) "Выбранные треки не будут воспроизводиться"
            else "Треки возвращены в воспроизведение"
        )
    }

    private fun deleteSelectedTracks() {
        val ids = selection.toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Удалить из библиотеки: ${ids.size}?")
            .setMessage(
                "Файлы останутся на устройстве. Эти треки будут исключены из библиотеки и воспроизведения, в том числе после обновления библиотеки."
            )
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                library.excludeTracks(ids)
                removeUnavailableFromQueue()
                model.reload()
                selection.clear()
                render()
            }
            .show()
    }

    private fun startTrack(track: Track) {
        if (track.uri in library.hiddenTracks()) {
            toast("Трек скрыт от воспроизведения. Выбери его и нажми «Вернуть»")
            return
        }
        val c = controller ?: return toast("Плеер ещё подключается")
        prefs.edit().putBoolean("onlinePlayerOrigin", false).apply()
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
        val d =
            c.duration.takeIf { it != C.TIME_UNSET && it > 0 }
                ?: c.currentMediaItem?.mediaMetadata?.extras?.getLong("onlineDuration")
                ?: 0L
        position.text =
            getString(
                dev.alex.folderplayer.R.string.duration_format,
                clock(c.currentPosition),
                clock(d),
            )
        onlineSection.updateNowPlaying(now.text.toString(), position.text.toString())
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
                if (id?.startsWith("afp-yandex://") == true)
                    OnlineLibraryActions(this)
                        .contains("favorites", Uri.parse(id).lastPathSegment.orEmpty())
                else !libraryLoading && id != null && id in library.playlist("Избранное"),
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
                        local?.duration
                            ?: item.mediaMetadata.extras?.getLong("onlineDuration")
                            ?: 0L,
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
                        1 -> {
                            if (track.uri in library.hiddenTracks())
                                toast(
                                    "Трек скрыт от воспроизведения. Сначала верни его кнопкой глаза"
                                )
                            else controller?.addMediaItem(track.item())
                        }
                        2 ->
                            AlertDialog.Builder(this)
                                .setTitle(track.title)
                                .setMessage(
                                    "${track.artist}\nФайл: ${track.filename.ifBlank { androidx.documentfile.provider.DocumentFile.fromSingleUri(this,Uri.parse(track.sourceUri))?.name ?: "Неизвестно" }}\nПапка: ${track.folder}\n${clock(track.duration)}"
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
        if (track.uri.startsWith("afp-yandex://")) {
            toast("Смешанные сохраняемые плейлисты — следующий этап. Избранное доступно кнопкой ♥")
            return
        }
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

    private fun importExtensionFolder(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                check(
                    androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)
                        ?.isDirectory == true
                )
                library.setRoots((library.roots() + uri.toString()).distinct())
                scan()
            }
            .onFailure {
                toast("Подтверди папку музыки для доступа плеера")
                documents.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                        .addFlags(
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                        ),
                    DocumentRequest.MUSIC_FOLDER,
                )
            }
    }

    private fun settings() {
        SettingsMenu.show(
            this,
            { entry ->
                ExternalExtensions.open(this, entry) { intent ->
                    documents.launch(intent, DocumentRequest.EXTENSION_FOLDER)
                }
            },
        ) { action ->
            when (action) {
                SettingsMenu.Action.ADD_FOLDER ->
                    documents.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            ),
                        DocumentRequest.MUSIC_FOLDER,
                    )
                SettingsMenu.Action.FOLDERS -> folders()
                SettingsMenu.Action.SCAN -> scan()
                SettingsMenu.Action.SEEK ->
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
                SettingsMenu.Action.SLEEP ->
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
                SettingsMenu.Action.EXTENSIONS -> extensions()
                SettingsMenu.Action.BOOKS -> startActivity(Intent(this, BooksActivity::class.java))
                SettingsMenu.Action.CRASH -> CrashNotes.show(this)
                SettingsMenu.Action.THEME -> ThemePicker.show(this)
                SettingsMenu.Action.ABOUT -> about()
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
        if (scanWork.state().busy) return
        LocalArtwork.clear()
        // Capture only the application-owned library, never this Activity.
        val source = Library(applicationContext)
        scanWork.start("library") { _, _ -> source.scan() }
    }

    private fun showScan(state: RetainedOperation.Snapshot<Library.ScanResult>) {
        scanning = state.busy
        val result = state.value
        if (result != null || state.failure != null) {
            // Consume completion before rendering; rotation must not repeat notifications.
            scanWork.cancel()
            if (result != null) {
                model.scanned(result.tracks)
                tracks = result.tracks
                mediaLoadedId = null
                queueTimeline = null
                queueTracks = null
                reorderPlayingQueue(prefs.getString("queueSource", "").orEmpty(), true)
                toast(
                    "В библиотеке ${result.tracks.size} песен" +
                        (if (result.retainedRoots > 0)
                            ". Сохранены данные недоступных папок: ${result.retainedRoots}"
                        else "") +
                        if (result.errors > 0)
                            ". Не удалось прочитать файлов/папок: ${result.errors}"
                        else ""
                )
            } else toast("Не удалось обновить библиотеку")
        }
        render()
    }

    private fun onDocumentResult(
        request: DocumentRequest,
        activityResult: androidx.activity.result.ActivityResult,
    ) {
        val resultCode = activityResult.resultCode
        val data = activityResult.data
        if (request == DocumentRequest.EXTENSION_FOLDER && resultCode == RESULT_OK) {
            data?.data?.let { importExtensionFolder(it) }
            return
        }
        if (request == DocumentRequest.DOCUMENT_TRANSLATION) {
            if (page == "translation") currentTrack()?.let { translate(it, true) }
            return
        }
        if (request == DocumentRequest.IMPORT_ENGINE && resultCode == RESULT_OK)
            data?.data?.let { extensionManager.importEngine(it) }
        if (request == DocumentRequest.IMPORT_MODEL && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                cancelTranslation()
                val track = currentTrack()
                screens.setTranslation(track?.uri, "", "Устанавливаем и проверяем языковой пакет…")
                background.submit({ ctx ->
                    ctx.contentResolver.openFileDescriptor(uri, "r")?.use {
                        TranslationClient(ctx).install(it)
                    } ?: error("Не удалось открыть пакет")
                }) { result ->
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
        if (request == DocumentRequest.IMPORT_LYRICS && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                background.submit({ ctx ->
                    LyricsStore(ctx).importText(uri).also {
                        LyricsStore(ctx).save(target, it)
                        ctx.getSharedPreferences("playback", 0)
                            .edit()
                            .remove("lyricsSource:$target")
                            .apply()
                    }
                }) { result ->
                    if (alive)
                        result
                            .onSuccess { tracks.find { it.uri == target }?.let { localMedia(it) } }
                            .onFailure { toast(it.message ?: "Не удалось импортировать текст") }
                }
            }
        if (request == DocumentRequest.EXPORT_LYRICS && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                background.submit({ ctx ->
                    val text = LyricsStore(ctx).read(target) ?: error("Нет сохранённого текста")
                    ctx.contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось открыть файл")
                }) { result ->
                    if (alive)
                        toast(
                            if (result.isSuccess) "Текст экспортирован"
                            else "Не удалось экспортировать текст"
                        )
                }
            }
        if (request == DocumentRequest.MUSIC_FOLDER && resultCode == RESULT_OK)
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

    override fun onSaveInstanceState(out: Bundle) {
        model.online = onlineSection.snapshot()
        out.putBundle("onlineBrowser", onlineSection.saveNavigation())
        out.putInt("libraryPage", libraryPage)
        out.putInt("libraryScroll", (list.parent as? ScrollView)?.scrollY ?: 0)
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
                SongMenu.Action.SAVE_MP3 ->
                    YandexSaveActivity.open(
                        this,
                        track.uri,
                        track.title,
                        track.artist,
                        controller
                            ?.currentMediaItem
                            ?.takeIf { it.mediaId == track.uri }
                            ?.mediaMetadata
                            ?.artworkUri
                            ?.toString()
                            .orEmpty(),
                    )
                SongMenu.Action.SHARE -> YandexTrackShare.show(this, track.uri, track.title)
                SongMenu.Action.SEARCH -> {
                    showPage("lyrics")
                    directLyrics(track)
                }
                SongMenu.Action.IMPORT -> {
                    lyricTarget = track.uri
                    documents.launch(
                        Intent(Intent.ACTION_OPEN_DOCUMENT)
                            .setType("*/*")
                            .addCategory(Intent.CATEGORY_OPENABLE),
                        DocumentRequest.IMPORT_LYRICS,
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
                    background.submit({ ctx -> SongContent(ctx).load(track).orEmpty() }) { result ->
                        val raw = result.getOrDefault("")

                        if (alive)
                            when (action) {
                                SongMenu.Action.EDIT -> editLyrics(track, raw)
                                SongMenu.Action.TIMING -> markLyrics(track, raw)
                                SongMenu.Action.EXPORT ->
                                    if (raw.isBlank()) toast("Сначала добавь текст")
                                    else {
                                        lyricTarget = track.uri
                                        val timed = LyricsDocument(raw).cues.isNotEmpty()
                                        documents.launch(
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
                                            DocumentRequest.EXPORT_LYRICS,
                                        )
                                    }
                                else -> Unit
                            }
                    }
            }
        }
    }

    private val thumbnails by lazy { LibraryThumbnails(applicationContext, artworkWorker, handler) }
    private val artworkWorker = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun currentTrack(): Track? {
        val item = controller?.currentMediaItem ?: return null
        tracks
            .find { it.uri == item.mediaId }
            ?.let {
                return it
            }
        if (OnlinePlayback.trackId(Uri.parse(item.mediaId)) == null) return null
        val meta = item.mediaMetadata
        return Track(
            item.mediaId,
            meta.title?.toString().orEmpty(),
            meta.artist?.toString().orEmpty(),
            meta.extras?.getLong("onlineDuration") ?: 0L,
            "Яндекс Музыка",
        )
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
            if (prefs.getBoolean("onlinePlayerOrigin", false)) onlineSection.show()
        } else pageMotion.leavePage { if (alive) showPage(target) }
    }

    private fun previewPlayer(travel: Float) {
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
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
        val cover = controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString().orEmpty()
        background.submit({ ctx ->
            val bitmap =
                runCatching {
                        if (track.uri.startsWith("afp-yandex://")) OnlineArtwork(ctx).load(cover)
                        else LocalArtwork.read(ctx, track.sourceUri, track.artworkUri)
                    }
                    .getOrNull()
            val lyrics = runCatching { SongContent(ctx).load(track)?.let { LyricsDocument(it) } }

            bitmap to lyrics
        }) { result ->
            val (bitmap, lyrics) =
                result.getOrElse {
                    return@submit
                }

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
        background.submit({ ctx ->
            val source =
                SongContent(ctx).load(track)?.let { LyricsDocument(it).plain }
                    ?: error("Сначала добавь текст")
            source to TranslationClient(ctx).availableModels().toList()
        }) { result ->
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
        background.submit({ ctx ->
            val document = LyricsDocument(item.text)
            LyricsStore(ctx).save(track.uri, item.text)
            SearchIdentity(ctx).save(track.uri, SongQuery(item.title, item.artist))
            ctx.getSharedPreferences("playback", 0)
                .edit()
                .putString(
                    "lyricsSource:${track.uri}",
                    "Источник: LRCLIB · ${item.title} — ${item.artist}",
                )
                .apply()
            document
        }) { result ->
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

    private val lyricsEditorDelegate = lazy {
        LyricsEditor(
            this,
            { controller },
            { library.hiddenTracks() },
            { track ->
                if (page != "library" && track.uri == controller?.currentMediaItem?.mediaId) {
                    mediaLoadedId = null
                    showPage("lyrics")
                    true
                } else false
            },
            { track ->
                lyricTarget = track.uri
                documents.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    DocumentRequest.IMPORT_LYRICS,
                )
            },
            { track, timed ->
                lyricTarget = track.uri
                documents.launch(
                    Intent(Intent.ACTION_CREATE_DOCUMENT)
                        .setType("text/plain")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(
                            Intent.EXTRA_TITLE,
                            track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_") +
                                (if (timed) ".lrc" else ".txt"),
                        ),
                    DocumentRequest.EXPORT_LYRICS,
                )
            },
            lyricsSearch,
            background,
            networkWork,
        )
    }
    private val lyricsEditor
        get() = lyricsEditorDelegate.value

    private fun localMedia(track: Track) = lyricsEditor.localMedia(track)

    private fun manageMedia(track: Track) = lyricsEditor.manageMedia(track)

    private fun editLyrics(track: Track, initial: String) = lyricsEditor.editLyrics(track, initial)

    private fun markLyrics(track: Track, raw: String) = lyricsEditor.markLyrics(track, raw)

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
            label.setTextColor(if (current) PlayerScreens.accent else AppAppearance.textColor)
            label.setTypeface(null, if (current) Typeface.BOLD else Typeface.NORMAL)
            label.isSelected = current
            (label.parent as? View)?.background =
                android.graphics.drawable.GradientDrawable().apply {
                    setColor(if (current) AppAppearance.palette.selected else PlayerScreens.bg)
                    cornerRadius = dp(14).toFloat()
                }
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            LocalArtwork.clear()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        if (intent?.action == "dev.alex.folderplayer.action.IMPORT_FOLDER") {
            intent.action = null
            intent.data
                ?.takeIf { it.scheme == "content" }
                ?.let { uri ->
                    AlertDialog.Builder(this)
                        .setTitle("Добавить папку музыки?")
                        .setMessage("Плеер прочитает аудиофайлы в выбранной папке и её подпапках.")
                        .setPositiveButton("Добавить") { _, _ -> importExtensionFolder(uri) }
                        .setNegativeButton("Отмена", null)
                        .show()
                }
        }
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
                documents.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("*/*")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    DocumentRequest.IMPORT_MODEL,
                )
            },
            { if (alive && page == "translation") currentTrack()?.let { translate(it, true) } },
            online = { onlineSection.show() },
            updates = { appUpdates.check() },
            importApk = {
                documents.launch(
                    Intent(Intent.ACTION_OPEN_DOCUMENT)
                        .setType("application/vnd.android.package-archive")
                        .addCategory(Intent.CATEGORY_OPENABLE),
                    DocumentRequest.IMPORT_ENGINE,
                )
            },
        )
    }

    private fun extensions() {
        extensionManager.show()
    }

    private val onlineSection: OnlineSection by lazy {
        OnlineSection(
            this,
            { raw, index, mode, done ->
                val c = controller
                if (c == null)
                    done(if (mode == "remove") null else "Плеер ещё подключается. Повтори запуск")
                else {
                    val request =
                        c.sendCustomCommand(
                            SessionCommand(PlaybackCommands.START_ONLINE_QUEUE, Bundle.EMPTY),
                            Bundle().apply {
                                putString("queueId", raw)
                                putInt("index", index)
                                putString("mode", mode)
                            },
                        )
                    request.addListener(
                        {
                            if (alive) {
                                val error =
                                    runCatching { request.get().resultCode }
                                        .fold(
                                            {
                                                if (it == SessionResult.RESULT_SUCCESS) null
                                                else "Плеер отклонил очередь. Повтори действие"
                                            },
                                            {
                                                "Не удалось передать очередь плееру. Повтори действие"
                                            },
                                        )
                                done(error)
                                if (error == null && mode == "play") {
                                    prefs.edit().putBoolean("onlinePlayerOrigin", true).apply()
                                    showPage("player")
                                }
                                updatePlayer()
                            }
                        },
                        java.util.concurrent.Executor { handler.post(it) },
                    )
                }
            },
            openPlayer = {
                prefs.edit().putBoolean("onlinePlayerOrigin", true).apply()
                showPage("player")
            },
        )
    }

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
        if (!restoredBrowseState && !libraryLoading) restorePlayingSource()
        refreshTicker()
    }

    override fun onStop() {
        restoredBrowseState = false
        translationForeground = false
        screenStarted = false
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    override fun onDestroy() {
        pageMotion.cancel()
        scanWork.detach()
        translationWork.detach()
        extensionManager.close()
        appUpdates.close()
        onlineSection.close()
        browser.close()
        thumbnails.close()
        artworkWorker.shutdownNow()
        lyricsWork.detach()
        albumYears.close()
        alive = false
        if (lyricsEditorDelegate.isInitialized()) lyricsEditor.close()
        networkWork.close()
        handler.removeCallbacksAndMessages(null)
        background.close()
        future?.let { MediaController.releaseFuture(it) }
        super.onDestroy()
    }
}
