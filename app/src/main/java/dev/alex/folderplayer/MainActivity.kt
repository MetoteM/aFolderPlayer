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
    internal lateinit var root: FrameLayout
    internal lateinit var screens: PlayerScreens
    internal var page = "library"
    internal lateinit var pageMotion: SheetMotion
    internal var soundReturn = "player"
    internal var lyricsRequestedId: String? = null
    internal lateinit var lyricsWork: RetainedOperation<List<LyricsSearch.Result>>
    internal var directId: String? = null
    internal var directResults = emptyList<LyricsSearch.Result>()
    internal var mediaGeneration = 0
    internal var mediaLoadedId: String? = null
    internal var queueTimeline: androidx.media3.common.Timeline? = null
    internal var queueTracks: List<Track>? = null
    internal lateinit var library: Library
    internal lateinit var layout: LinearLayout
    internal lateinit var list: LinearLayout
    internal lateinit var now: TextView
    internal lateinit var position: TextView
    internal lateinit var seek: SeekBar
    internal lateinit var play: Button
    internal lateinit var selectionButton: Button
    internal lateinit var identifyButton: Button
    internal lateinit var hideTracksButton: Button
    internal lateinit var deleteTracksButton: Button
    internal lateinit var bulkButton: Button
    internal lateinit var moreTools: LinearLayout
    internal lateinit var browserTitle: TextView
    internal lateinit var browserCount: TextView
    internal lateinit var search: EditText
    internal var controller: MediaController? = null
    private var future: ListenableFuture<MediaController>? = null
    internal var tracks = emptyList<Track>()
    internal var visible = emptyList<Track>()
    internal var selectedPlaylist: String? = null
    internal var query = ""
    internal val selection = linkedSetOf<String>()
    internal var selecting = false
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
    internal val onlineSection: OnlineSection by lazy { OnlineSection(this, { raw,index,mode,done ->
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

    internal val appUpdates: AppUpdates by lazy { AppUpdates(this, extensionManager) }
    internal val thumbnails by lazy { LibraryThumbnails(applicationContext,artworkWorker,handler) }
    internal val artworkWorker=java.util.concurrent.Executors.newSingleThreadExecutor()
    internal var libraryPage = 0
    internal var pendingScrollY: Int? = null
    internal val renderSearch = Runnable { if (alive) render() }
    internal var lastBrowserSource: String? = null
    internal val albumYears by lazy {
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
    internal var scanning = false
    internal var dragging = false
    internal var alive = true
    internal val trackLabels = linkedMapOf<String, TextView>()
    internal var highlightedId: String? = null
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
    internal var screenStarted = false
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

    internal fun refreshTicker() {
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

    internal fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

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

    internal fun reorderPlayingQueue(name: String, refreshSource: Boolean = false) {
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

    internal fun currentTrack(): Track? {
        val item=controller?.currentMediaItem ?: return null
        tracks.find { it.uri==item.mediaId }?.let { return it }
        if(OnlinePlayback.trackId(Uri.parse(item.mediaId))==null)return null
        val meta=item.mediaMetadata
        return Track(item.mediaId,meta.title?.toString().orEmpty(),meta.artist?.toString().orEmpty(),
            meta.extras?.getLong("onlineDuration") ?: 0L,"Яндекс Музыка")
    }

    override fun onBackPressed() {
        if (page != "library") backPage() else super.onBackPressed()
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

    internal val extensionManager: ExtensionManager by lazy {
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
