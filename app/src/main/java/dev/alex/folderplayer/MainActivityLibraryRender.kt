package dev.alex.folderplayer

import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*

/** Library list rendering & selection tools: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.updateSelectionTools() {
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

    /** Scroll position to apply on the first render after recreation (rotation). */
internal fun MainActivity.render() {
        val listRef = runCatching { list }.getOrNull() ?: return  // lateinit not yet initialized
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
        browserTitle.text = selectedPlaylist ?: afp(R.string.library)
        browserCount.text = if (scanning) "Проверяем папки…" else "${visible.size} песен"
        if (visible.isEmpty())
            list.addView(
                label(
                    if (library.roots().isEmpty())
                        "Добавь папку через меню ⋯.\nВ библиотеку попадут песни от 30 секунд."
                    else "Песен не найдено. Обнови библиотеку через меню ⋯.",
                    15f,
                )
            )
        val favorites = library.playlist(afp(R.string.favorites))
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
        if(lastPage>0)list.addView(label("Страница ${libraryPage+1} из ${lastPage+1}",12f))
        visible.drop(libraryPage*pageSize).take(pageSize).forEach { track ->
            val r =
                row().apply {
                    setPadding(dp(8), dp(6), dp(4), dp(6))
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(PlayerScreens.bg)
                            cornerRadius = dp(12).toFloat()
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
                        android.text.style.RelativeSizeSpan(0.78f),
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
            val info = label(caption, 15f).apply { text = styled }
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
            r.addView(heart, LinearLayout.LayoutParams(dp(44), dp(48)))
            list.addView(
                r,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(4), dp(2), dp(4), dp(2)) },
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

