package dev.alex.folderplayer

import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import android.view.View
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

import android.widget.*
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException

/** Main-screen chrome (library toolbar, list, player row, controller): extracted from onCreate (roadmap 4.2). */
internal fun MainActivity.buildUi(state: Bundle?) {
    buildChrome()
    connectController(state)
}

/**
 * Rebuilds the static chrome. Called from onCreate, and again on
 * onConfigurationChanged so a rotated phone re-flows without recreating the
 * Activity (the manifest opts out of recreation).
 *
 * Landscape follows a content-first UX rule: the track list is the primary
 * surface, so ALL toolbar rows (sources, tools, selection actions, recognise)
 * collapse into one horizontally scrollable compact strip, the title shrinks
 * and the search field gets a fixed compact height. That leaves roughly 60%
 * of the screen height to the list — previously four stacked 48dp rows plus
 * the large heading left "almost only buttons" visible.
 *
 * "Quiet chrome" (UI refactor A): title, toolbar and search live in a single
 * header container that auto-hides when the list is scrolled down and slides
 * back on the first scroll up — in BOTH orientations. Playback strip, seek
 * bar and transport stay pinned, so controls never disappear completely.
 */
internal fun MainActivity.buildChrome() {
    layout.removeAllViews()
    // NOTE: in landscape screenWidthDp is the LONG side (~800dp on a phone),
    // so a `screenWidthDp < 600` check never fires. The scarce dimension in
    // landscape is the short side, so measure screenHeightDp instead.
    val landscape =
        resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE &&
            (resources.configuration.screenHeightDp < 600)
    val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun target(): LinearLayout = if (landscape) left else layout
    fun playerTarget(): LinearLayout = if (landscape) right else layout
    chromeHidden = false
    val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    chromeHeader = header
    // Collapsing toolbar: in landscape every toolbar row is parked in one
    // horizontally scrollable strip instead of eating vertical space.
    val strip = if (landscape) HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false } else null
    val stripRow = if (landscape) row() else null
    fun attachToolbar(view: View) {
        if (landscape) stripRow!!.addView(view) else header.addView(view)
    }
    fun toolParams(): LinearLayout.LayoutParams =
        if (landscape) LinearLayout.LayoutParams(dp(124), dp(44)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
        else weight().apply { height = dp(48) }
    fun wideToolParams(): LinearLayout.LayoutParams =
        if (landscape) LinearLayout.LayoutParams(dp(150), dp(44)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) }
        else LinearLayout.LayoutParams(0, dp(48), 2f)
        browserTitle = label(afp(R.string.library), if (landscape) 15f else 19f).apply {
            setPadding(dp(12), dp(10), dp(8), dp(2))
            letterSpacing = 0.01f
        }
        header.addView(browserTitle)
        browserCount = label("", 12f).apply {
            setTextColor(PlayerScreens.muted)
            setPadding(dp(12), dp(0), dp(8), dp(6))
        }
        header.addView(browserCount)
        val sources = row()
        if (landscape) {
            sources.addView(
                button("На устройстве") { selectedPlaylist=null;render() },
                LinearLayout.LayoutParams(dp(130), dp(44)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) },
            )
            sources.addView(
                button(afp(R.string.yandex_music)) { onlineSection.show() },
                LinearLayout.LayoutParams(dp(150), dp(44)).apply { setMargins(dp(3), dp(2), dp(3), dp(2)) },
            )
        } else {
            sources.addView(button("На устройстве") { selectedPlaylist=null;render() },weight())
            sources.addView(button(afp(R.string.yandex_music)) { onlineSection.show() },weight())
        }
        attachToolbar(sources)
        val tools = row()
        moreTools = row()
        fun command(kind: IconButton.Kind, label: String, action: () -> Unit) =
            IconButton(this, kind, label, action)
        tools.addView(
            command(IconButton.Kind.LIBRARY, afp(R.string.library)) {
                selectedPlaylist = null
                selecting = false
                selection.clear()
                render()
            },
            toolParams(),
        )
        tools.addView(
            command(IconButton.Kind.PLAYLIST, "Плейлисты") { choosePlaylist() },
            toolParams(),
        )
        tools.addView(
            command(IconButton.Kind.SORT, "Сортировка") { sortDialog() },
            toolParams(),
        )
        selectionButton =
            command(IconButton.Kind.SELECT, "Выбрать треки") {
                selecting = !selecting
                selection.clear()
                render()
            }
        tools.addView(selectionButton, toolParams())
        tools.addView(
            command(IconButton.Kind.MENU, "Меню") { settings() },
            toolParams(),
        )
        bulkButton = button("В плейлист") { bulkAdd() }
        moreTools.addView(bulkButton, toolParams())
        attachToolbar(tools)
        hideTracksButton = button("👁") { toggleSelectedTracksHidden() }
        deleteTracksButton = button(afp(R.string.delete)) { deleteSelectedTracks() }
        moreTools.addView(hideTracksButton, toolParams())
        moreTools.addView(deleteTracksButton, toolParams())
        attachToolbar(moreTools)
        val quick = row()
        identifyButton = button(afp(R.string.recognize_song)) { identifySong() }
        quick.addView(
            identifyButton,
            wideToolParams(),
        )
        attachToolbar(quick)
        if (landscape) {
            header.addView(strip, LinearLayout.LayoutParams(-1, dp(48)))
        }
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
                    chromeHeader?.takeIf { chromeHidden }?.let {
                        chromeHidden = false
                        it.animate().translationY(0f).setDuration(140).start()
                    }
                    handler.removeCallbacks(renderSearch)
                    handler.postDelayed(renderSearch, 180)
                }

                override fun afterTextChanged(s: android.text.Editable?) {}
            }
        )
        if (landscape) {
            header.addView(
                search,
                LinearLayout.LayoutParams(-1, dp(38)).apply { setMargins(dp(8), dp(2), dp(8), dp(4)) },
            )
        } else {
            header.addView(
                search,
                LinearLayout.LayoutParams(-1, dp(42)).apply { setMargins(dp(8), dp(4), dp(8), dp(6)) },
            )
        }
        val scroller = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroller.addView(list)
        target().addView(header, LinearLayout.LayoutParams(-1, -2))
        target().addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        scroller.setOnScrollChangeListener { _, _, scrollY, _, oldScrollY ->
            val headerView = chromeHeader ?: return@setOnScrollChangeListener
            val down = scrollY - oldScrollY
            when {
                down > dp(4) && scrollY > dp(24) && !chromeHidden -> {
                    chromeHidden = true
                    headerView.animate().translationY(-headerView.height.toFloat()).setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                down < -dp(2) && chromeHidden -> {
                    chromeHidden = false
                    headerView.animate().translationY(0f).setDuration(180)
                        .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                }
                scrollY == 0 && chromeHidden -> {
                    chromeHidden = false
                    headerView.animate().translationY(0f).setDuration(180).start()
                }
            }
        }
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
        playerTarget().addView(current, LinearLayout.LayoutParams(-1, -2))
        seek =
            SeekBar(this).apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(PlayerScreens.accent)
                thumbTintList = android.content.res.ColorStateList.valueOf(PlayerScreens.accent)
            }
        playerTarget().addView(seek)
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
        playerTarget().addView(controls)
        if (landscape) {
            val columns = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            columns.addView(left, LinearLayout.LayoutParams(0, -1, 1f))
            columns.addView(
                right,
                LinearLayout.LayoutParams(0, -1, 0.85f).apply { setMargins(dp(6), 0, dp(6), 0) },
            )
            layout.addView(columns, LinearLayout.LayoutParams(-1, 0, 1f))
        }
}

/** MediaController wiring + first render/page: runs once per Activity, not on rotation. */
internal fun MainActivity.connectController(state: Bundle?) {
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
