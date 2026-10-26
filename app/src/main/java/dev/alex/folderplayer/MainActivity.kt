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

class MainActivity : ThemedActivity() {
    private lateinit var root: FrameLayout
    internal lateinit var screens: PlayerScreens
    internal var page = "library"
    private lateinit var pageMotion: SheetMotion
    private var soundReturn = "player"
    private var lyricsRequestedId: String? = null
    internal lateinit var lyricsWork: RetainedOperation<List<LyricsSearch.Result>>
    internal var directId: String? = null
    internal var directResults = emptyList<LyricsSearch.Result>()
    private var mediaGeneration = 0
    internal var mediaLoadedId: String? = null
    private var queueTimeline: androidx.media3.common.Timeline? = null
    private var queueTracks: List<Track>? = null
    internal lateinit var library: Library
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
    internal var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    private var tracks = emptyList<Track>()
    private var visible = emptyList<Track>()
    private var selectedPlaylist: String? = null
    private var query = ""
    private val selection = linkedSetOf<String>()
    private var selecting = false
    internal var lyricTarget: String? = null
    internal var lyricUpdate: (() -> Unit)? = null
    internal val lyricsSearch by lazy {
        LyricsSearch(SearchConnections(this)).also {
            it.restoreCooldown(prefs.getLong("lyricsRetryUntil", 0))
        }
    }
    internal val translationClient by lazy { TranslationClient(this) }
    internal lateinit var translationWork: RetainedOperation<SongTranslationResult>
    internal var translationId: String? = null
    internal val networkExecutor = Executors.newSingleThreadExecutor()
    internal var networkCancel: LyricsSearch.Cancel? = null
    internal var networkTask: java.util.concurrent.Future<*>? = null
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
    internal val lyricStore by lazy { LyricsStore(this) }
    private var scanning = false
    private var dragging = false
    internal var alive = true
    private val trackLabels = linkedMapOf<String, TextView>()
    private var highlightedId: String? = null
    internal val executor = Executors.newSingleThreadExecutor()
    internal val handler = Handler(Looper.getMainLooper())

    /**
     * Single seam for the background→UI hand-off. Replaces the previous ~13
     * copies of `executor.execute { handler.post { if (alive) ... } }`: the
     * alive guard lives in exactly one place, so a forgotten check can no
     * longer touch dead views after onDestroy().
     */
    internal fun postUi(ui: () -> Unit) {
        handler.post { if (alive) ui() }
    }

    internal fun <T> runAsync(task: () -> T, consume: (Result<T>) -> Unit) {
        executor.execute {
            val result = runCatching(task)
            postUi { consume(result) }
        }
    }
    internal val prefs by lazy { getSharedPreferences("playback", MODE_PRIVATE) }
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
        CrashNotes.install(this)
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
        libraryPage = state?.getInt("libraryPage", 0) ?: 0
        state?.getInt("libraryScrollY", -1)?.takeIf { it >= 0 }?.let { pendingScrollY = it }
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
                        togglePlay()
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
                        runAsync(
                            {
                                track?.let { loadLyrics(it) }?.let { LyricsDocument(it).plain }
                                    ?: ""
                            },
                        ) { result ->
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
        hideTracksButton = button("👁") { toggleSelectedTracksHidden() }
        deleteTracksButton = button("Удалить") { deleteSelectedTracks() }
        moreTools.addView(hideTracksButton, weight().apply { height = dp(48) })
        moreTools.addView(deleteTracksButton, weight().apply { height = dp(48) })
        layout.addView(moreTools)
        val quick = row()
        identifyButton = button("Определить песню") { identifySong() }
        quick.addView(
            identifyButton,
            LinearLayout.LayoutParams(0, dp(48), 2f),
        )

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
                if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) startActivity(Intent(this, BooksActivity::class.java))
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

        play = button("▶") { togglePlay() }
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

    internal fun row() =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    internal fun weight() =
        LinearLayout.LayoutParams(0, -2, 1f).apply { setMargins(dp(3), dp(4), dp(3), dp(4)) }

    internal fun label(text: String, size: Float = 16f) =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(AppAppearance.textColor)
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }

    internal fun button(text: String, action: () -> Unit) =
        Button(this).apply {
            this.text = text
            isAllCaps = false
            textSize = 14f
            minWidth = 0
            minimumWidth = 0
            minimumHeight = dp(48)
            setTextColor(AppAppearance.textColor)
            background =
                android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(AppAppearance.palette.control),
                    android.graphics.drawable.GradientDrawable().apply {
                        setColor(PlayerScreens.surface)
                        cornerRadius = dp(16).toFloat()
                    },
                    null,
                )
            setOnClickListener { action() }
        }

    internal fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    internal fun clock(ms: Long): String {
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
        identifyButton.visibility = if (selecting) View.GONE else View.VISIBLE
        val allHidden = selection.isNotEmpty() && selection.all { it in library.hiddenTracks() }
        hideTracksButton.text = if (allHidden) "👁 Вернуть" else "👁 Скрыть"
        hideTracksButton.contentDescription = if (allHidden) "Вернуть в воспроизведение" else "Не воспроизводить"
        deleteTracksButton.contentDescription = "Удалить выбранные треки из библиотеки"
        listOf(hideTracksButton, deleteTracksButton).forEach {
            it.isEnabled = selection.isNotEmpty()
            it.alpha = if (it.isEnabled) 1f else 0.45f
        }
    }

    private var libraryPage = 0
    private var lastBrowserSource:String? = null
    /** Scroll position to apply on the first render after recreation (rotation). */
    private var pendingScrollY: Int? = null
    private val renderSearch = Runnable { if (alive) render() }

    private fun render() {
        if (!::list.isInitialized) return
        updateSelectionTools()
        val scrollView = list.parent as? ScrollView
        val scrollY =
            pendingScrollY
                ?.also { pendingScrollY = null }
                ?: scrollView?.scrollY
                ?: 0
        thumbnails.begin(scrollView)
        list.removeAllViews()
        list.post { thumbnails.loadVisible() }
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
        val hidden = library.hiddenTracks()
        if (selecting)
            list.addView(
                button("Выбрать все найденные (${selection.size} выбрано)") {
                    selection.addAll(visible.map { it.uri })
                    render()
                }
            )
        if(lastBrowserSource!=selectedPlaylist){libraryPage=0;lastBrowserSource=selectedPlaylist}
        val pageSize=200
        val lastPage=((visible.size-1).coerceAtLeast(0))/pageSize
        libraryPage=libraryPage.coerceIn(0,lastPage)
        if(lastPage>0)list.addView(label("Страница ${libraryPage+1} из ${lastPage+1}",14f))
        visible.drop(libraryPage*pageSize).take(pageSize).forEach { track ->
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
            if (track.artworkUri.isNotBlank()) {
                val thumb = ImageView(this).apply { scaleType=ImageView.ScaleType.CENTER_CROP; contentDescription="Обложка: ${track.albumName()}" }
                r.addView(thumb, LinearLayout.LayoutParams(dp(44),dp(44)).apply { marginEnd=dp(8) })
                thumbnails.add(thumb,track.artworkUri)
                thumb.setOnClickListener { if(selecting){if(!selection.add(track.uri))selection.remove(track.uri);render()}else startTrack(track) }
            }
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
            if (track.uri in hidden) details.add("👁 Не воспроизводится")
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
        if(lastPage>0) {
            val navigation=row()
            navigation.addView(button("← Назад") { libraryPage--;render();scrollView?.post { scrollView.scrollTo(0,0) } }.apply { isEnabled=libraryPage>0 },weight())
            navigation.addView(button("Далее →") { libraryPage++;render();scrollView?.post { scrollView.scrollTo(0,0) } }.apply { isEnabled=libraryPage<lastPage },weight())
            list.addView(navigation)
        }
        highlightTrack(true)
        scrollView?.post { if (alive) scrollView.scrollTo(0, scrollY) }
    }

    private fun removeUnavailableFromQueue() {
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
        val playable = library.playableTracks().map { it.uri }.toSet()
        val onlineHidden=OnlineLibraryActions(this).ids("hidden")
        controller?.let { c ->
            for (index in c.mediaItemCount - 1 downTo 0) {
                if (!QueueEligibility.playable(c.getMediaItemAt(index).mediaId,playable,onlineHidden)) c.removeMediaItem(index)
            }
        }
    }

    private fun toggleSelectedTracksHidden() {
        if (selection.isEmpty()) return
        val hide = !selection.all { it in library.hiddenTracks() }
        library.setTracksHidden(selection.toSet(), hide)
        removeUnavailableFromQueue()
        render()
        toast(if (hide) "Выбранные треки не будут воспроизводиться" else "Треки возвращены в воспроизведение")
    }

    private fun deleteSelectedTracks() {
        val ids = selection.toSet()
        if (ids.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle("Удалить из библиотеки: ${ids.size}?")
            .setMessage("Файлы останутся на устройстве. Эти треки будут исключены из библиотеки и воспроизведения, в том числе после обновления библиотеки.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                library.excludeTracks(ids)
                removeUnavailableFromQueue()
                tracks = library.tracks()
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

    /** Single play/pause toggle for every entry point (sheet, transport row, lyrics screen). */
    internal fun togglePlay() {
        controller?.let {
            if (it.isPlaying) it.pause()
            else {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }
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
                        1 -> {
                            if (track.uri in library.hiddenTracks())
                                toast("Трек скрыт от воспроизведения. Сначала верни его кнопкой глаза")
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

    private fun importExtensionFolder(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            check(androidx.documentfile.provider.DocumentFile.fromTreeUri(this, uri)?.isDirectory == true)
            library.setRoots((library.roots() + uri.toString()).distinct())
            scan()
        }.onFailure {
            toast("Подтверди папку музыки для доступа плеера")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION), 100)
        }
    }

    private fun settings() {
        SettingsMenu.show(this) { action ->
            when (action) {
                SettingsMenu.Action.ADD_FOLDER ->
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                            .addFlags(
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                            ),
                        100,
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
        LocalArtwork.clear()
        if (scanning) return
        scanning = true
        render()
        runAsync({ library.scan() }) { result ->
            scanning = false
            result
                .onSuccess { (items, errors, retainedRoots) ->
                    tracks = items
                    mediaLoadedId=null;queueTimeline=null;queueTracks=null
                    render()
                    reorderPlayingQueue(prefs.getString("queueSource", "").orEmpty(), true)
                    toast(
                        "В библиотеке ${items.size} песен" +
                            (if (retainedRoots > 0) ". Сохранены данные недоступных папок: $retainedRoots" else "") +
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ExternalExtensions.REQUEST && resultCode == RESULT_OK) {
            data?.data?.let { importExtensionFolder(it) }
            return
        }
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
                runAsync({
                    contentResolver.openFileDescriptor(uri, "r")?.use {
                        translationClient.install(it)
                    } ?: error("Не удалось открыть пакет")
                }) { result ->
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
        if (requestCode == 101 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                runAsync({
                    lyricStore.importText(uri).also {
                        lyricStore.save(target, it)
                        prefs.edit().remove("lyricsSource:$target").apply()
                    }
                }) { result ->
                    result
                        .onSuccess {
                            tracks.find { it.uri == target }?.let { localMedia(it) }
                        }
                        .onFailure { toast(it.message ?: "Не удалось импортировать текст") }
                }
            }
        if (requestCode == 102 && resultCode == RESULT_OK)
            data?.data?.let { uri ->
                val target = lyricTarget ?: return@let
                runAsync({
                    val text = lyricStore.read(target) ?: error("Нет сохранённого текста")
                    contentResolver.openOutputStream(uri, "wt")?.use {
                        it.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("Не удалось открыть файл")
                }) { result ->
                    toast(
                        if (result.isSuccess) "Текст экспортирован"
                        else "Не удалось экспортировать текст"
                    )
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
        out.putInt("libraryPage", libraryPage)
        out.putInt(
            "libraryScrollY",
            (if (::list.isInitialized) list.parent as? ScrollView else null)?.scrollY ?: 0,
        )
        super.onSaveInstanceState(out)
    }

    private fun currentSongMenu(track: Track) {
        stopDirectLyrics()
        SongMenu.show(this, track.title, track.uri.startsWith("afp-yandex://")) { action ->
            when (action) {
                SongMenu.Action.SAVE_MP3 -> YandexSaveActivity.open(this,track.uri,track.title,track.artist,controller?.currentMediaItem?.takeIf { it.mediaId==track.uri }?.mediaMetadata?.artworkUri?.toString().orEmpty())
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
                    runAsync({ loadLyrics(track).orEmpty() }) { result ->
                        val raw = result.getOrNull().orEmpty()
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

    private val thumbnails by lazy { LibraryThumbnails(applicationContext,artworkWorker,handler) }
    private val artworkWorker=java.util.concurrent.Executors.newSingleThreadExecutor()

    internal fun currentTrack(): Track? {
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
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
        if (page != "library") return
        if (screens.view.visibility != View.VISIBLE) {
            screens.show("player")
            updatePlayer()
            currentTrack()?.let { loadScreenMedia(it) }
        }
        pageMotion.preview(root.height, travel)
    }

    internal fun showPage(value: String, animate: Boolean = true) {
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
                else LocalArtwork.read(this, track.sourceUri, track.artworkUri)
            }.getOrNull()
            val lyrics = runCatching { loadLyrics(track)?.let { LyricsDocument(it) } }
            postUi {
                if (
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

    internal var translationForeground = false


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
            intent.data?.takeIf { it.scheme == "content" }?.let { uri ->
                AlertDialog.Builder(this).setTitle("Добавить папку музыки?")
                    .setMessage("Плеер прочитает аудиофайлы в выбранной папке и её подпапках.")
                    .setPositiveButton("Добавить") { _, _ -> importExtensionFolder(uri) }
                    .setNegativeButton("Отмена", null).show()
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
        thumbnails.close()
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
