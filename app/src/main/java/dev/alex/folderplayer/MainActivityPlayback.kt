package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.SessionResult
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand

/** Playback commands, track menus & player-screen refresh: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.removeUnavailableFromQueue() {
        if (prefs.getString("activeBook", "").orEmpty().isNotEmpty()) return
        val playable = library.playableTracks().map { it.uri }.toSet()
        val onlineHidden=OnlineLibraryActions(this).ids("hidden")
        controller?.let { c ->
            for (index in c.mediaItemCount - 1 downTo 0) {
                if (!QueueEligibility.playable(c.getMediaItemAt(index).mediaId,playable,onlineHidden)) c.removeMediaItem(index)
            }
        }
    }

internal fun MainActivity.toggleSelectedTracksHidden() {
        if (selection.isEmpty()) return
        val hide = !selection.all { it in library.hiddenTracks() }
        library.setTracksHidden(selection.toSet(), hide)
        removeUnavailableFromQueue()
        render()
        toast(if (hide) "Выбранные треки не будут воспроизводиться" else "Треки возвращены в воспроизведение")
    }

internal fun MainActivity.deleteSelectedTracks() {
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

internal fun MainActivity.startTrack(track: Track) {
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

internal fun MainActivity.sendPlaybackCommand(c: MediaController, action: String, args: Bundle) {
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

internal fun MainActivity.repeatDialog() {
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

internal fun MainActivity.restorePlayingSource() {
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
internal fun MainActivity.togglePlay() {
        controller?.let {
            if (it.isPlaying) it.pause()
            else {
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.play()
            }
        }
    }

internal fun MainActivity.updatePlayer() {
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

internal fun MainActivity.skip(direction: Int) {
        controller?.let { c ->
            val step = prefs.getInt("seekSeconds", 10) * 1000L
            val target = (c.currentPosition + step * direction).coerceAtLeast(0)
            c.seekTo(if (c.duration > 0) target.coerceAtMost(c.duration) else target)
        }
    }

internal fun MainActivity.trackMenu(track: Track) {
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

internal fun MainActivity.moveTrack(track: Track, delta: Int) {
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

internal fun MainActivity.currentSongMenu(track: Track) {
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



internal fun MainActivity.openLyrics() {
        val track = currentTrack() ?: return toast("Сначала включи песню")
        lyricsRequestedId = track.uri
        mediaLoadedId = null
        showPage("lyrics")
    }

    @Deprecated("Framework back compatibility")
internal fun MainActivity.loadScreenMedia(track: Track) {
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


internal fun MainActivity.highlightTrack(force: Boolean) {
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

