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
    internal var future: ListenableFuture<MediaController>? = null
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

    /** SAF-выбор корневой папки библиотеки (замена requestCode 100). */
    internal val folderPicker =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let { if (alive) importExtensionFolder(it) }
        }

    /** Импорт текста песни из файла (замена requestCode 101). */
    internal val lyricsImporter =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { if (alive) importLyricsFrom(it) }
        }

    /** Экспорт текста песни в файл (замена requestCode 102). */
    internal val lyricsExporter =
        registerForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
        ) { uri -> uri?.let { if (alive) exportLyricsTo(it) } }

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
        val retained = lastCustomNonConfigurationInstance as? MainOperations
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
        screens = PlayerScreens(this, buildActions())
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
        buildUi(state)
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
        handleActivityResult(requestCode, resultCode, data)
    }

    override fun onRetainCustomNonConfigurationInstance(): Any =
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
            meta.extras?.getLong("onlineDuration") ?: 0L,afp(R.string.yandex_music))
    }

    override fun onBackPressed() {
        if (page != "library") backPage() else super.onBackPressed()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)
            LocalArtwork.clear()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (alive && ::layout.isInitialized && ::list.isInitialized) {
            buildChrome()
            render()
        }
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
                    .setPositiveButton(afp(R.string.add)) { _, _ -> importExtensionFolder(uri) }
                    .setNegativeButton(afp(R.string.cancel), null).show()
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

private class MainOperations(

        val translation: RetainedOperation<SongTranslationResult>,

        val lyrics: RetainedOperation<List<LyricsSearch.Result>>,

    )

}
