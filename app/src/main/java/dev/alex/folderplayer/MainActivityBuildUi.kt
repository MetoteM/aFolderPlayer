package dev.alex.folderplayer

import android.os.Bundle
import android.content.ComponentName
import android.content.Intent
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

import android.widget.*
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException

/** Main-screen chrome (library toolbar, list, player row, controller): extracted from onCreate (roadmap 4.2). */
internal fun MainActivity.buildUi(state: Bundle?) {
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
