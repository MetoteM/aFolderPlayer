package dev.alex.folderplayer

import android.content.Context
import android.widget.*

/** Library rows, selection and paging; navigation and playback remain host actions. */
internal class LibraryBrowser(
    context: Context,
    private val list: LinearLayout,
    private val browserTitle: TextView,
    private val browserCount: TextView,
    private val thumbnails: LibraryThumbnails,
    private val library: Library,
    private val actions: Actions,
) : AutoCloseable {
    interface Actions {
        fun refresh()

        fun reload()

        fun select(id: String, selected: Boolean, redraw: Boolean)

        fun selectAll(ids: List<String>)

        fun page(index: Int)

        fun play(track: Track)

        fun menu(track: Track)

        fun addToPlaylists(track: Track)
    }

    data class State(
        val tracks: List<Track>,
        val selectedPlaylist: String?,
        val query: String,
        val selecting: Boolean,
        val selection: Set<String>,
        val scanning: Boolean,
        val libraryLoading: Boolean,
        val libraryFailure: String?,
        val page: Int,
        val sort: Int,
    )

    data class Rendered(val visible: List<Track>, val labels: Map<String, TextView>, val page: Int)

    private val context = context
    private val ui = PlayerUi(context)
    private val trackLabels = linkedMapOf<String, TextView>()
    private var alive = true

    override fun close() {
        alive = false
        trackLabels.clear()
    }

    private fun row() = ui.row()

    private fun dp(value: Int) = ui.dp(value)

    private fun weight() = ui.weight()

    private fun label(text: String, size: Float = 16f) = ui.label(text, size)

    private fun button(text: String, action: () -> Unit) = ui.button(text, action)

    private fun clock(ms: Long) = ui.clock(ms)

    fun render(state: State): Rendered {

        if (state.libraryLoading && state.tracks.isEmpty()) {
            browserTitle.text = state.selectedPlaylist ?: "Библиотека"
            browserCount.text = "Загружаем библиотеку…"
            list.removeAllViews()
            list.addView(label("Загружаем библиотеку…", 16f))
            return Rendered(emptyList(), emptyMap(), state.page)
        }
        val scrollView = list.parent as? ScrollView
        val scrollY = scrollView?.scrollY ?: 0
        thumbnails.begin(scrollView)
        list.removeAllViews()
        list.post { thumbnails.loadVisible() }
        trackLabels.clear()
        val allowed = state.selectedPlaylist?.let { library.playlist(it) }
        val ordered = library.ordered(state.tracks, state.selectedPlaylist.orEmpty(), state.sort)
        val visible =
            ordered.filter {
                (allowed == null || it.uri in allowed) &&
                    (it.title.contains(state.query, true) || it.artist.contains(state.query, true))
            }
        browserTitle.text = state.selectedPlaylist ?: "Библиотека"
        browserCount.text =
            if (state.scanning) "Проверяем папки…"
            else if (state.libraryLoading) "Загружаем библиотеку…" else "${visible.size} песен"
        if (visible.isEmpty())
            list.addView(
                label(
                    if (state.libraryLoading) "Загружаем библиотеку…"
                    else if (state.libraryFailure != null) state.libraryFailure!!
                    else if (library.roots().isEmpty())
                        "Добавь папку через меню ⋯.\nВ библиотеку попадут песни от 30 секунд."
                    else "Песен не найдено. Обнови библиотеку через меню ⋯.",
                    16f,
                )
            )
        if (state.libraryFailure != null)
            list.addView(button("Повторить загрузку") { actions.reload() })
        val favorites = library.playlist("Избранное")
        val hidden = library.hiddenTracks()
        if (state.selecting)
            list.addView(
                button("Выбрать все найденные (${state.selection.size} выбрано)") {
                    actions.selectAll(visible.map { it.uri })
                }
            )
        var libraryPage = state.page
        val pageSize = 200
        val lastPage = ((visible.size - 1).coerceAtLeast(0)) / pageSize
        libraryPage = libraryPage.coerceIn(0, lastPage)
        if (lastPage > 0) list.addView(label("Страница ${libraryPage+1} из ${lastPage+1}", 14f))
        visible.drop(libraryPage * pageSize).take(pageSize).forEach { track ->
            val r =
                row().apply {
                    setPadding(dp(4), dp(4), dp(4), dp(4))
                    background =
                        android.graphics.drawable.GradientDrawable().apply {
                            setColor(PlayerScreens.bg)
                            cornerRadius = dp(14).toFloat()
                        }
                }
            if (state.selecting)
                r.addView(
                    CheckBox(context).apply {
                        isChecked = track.uri in state.selection
                        setOnCheckedChangeListener { _, yes ->
                            actions.select(track.uri, yes, false)
                        }
                    }
                )
            if (track.artworkUri.isNotBlank()) {
                val thumb =
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        contentDescription = "Обложка: ${track.albumName()}"
                    }
                r.addView(
                    thumb,
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(8) },
                )
                thumbnails.add(thumb, track.artworkUri)
                thumb.setOnClickListener {
                    if (state.selecting) {
                        actions.select(track.uri, track.uri !in state.selection, true)
                    } else actions.play(track)
                }
            }
            val number =
                if (state.selectedPlaylist != null)
                    (track.trackNumber ?: filenameTrackNumber(track))
                        ?.let { "№%02d · ".format(it) }
                        .orEmpty()
                else ""
            val details = mutableListOf(track.artist.ifBlank { track.folder })
            if (state.selectedPlaylist != null) {
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
                if (state.selecting) {
                    actions.select(track.uri, track.uri !in state.selection, true)
                } else actions.play(track)
            }
            info.setOnLongClickListener {
                actions.menu(track)
                true
            }
            r.addView(info, weight())
            val heart =
                button(if (track.uri in favorites) "♥" else "♡") {
                    library.toggleFavorite(track)
                    actions.refresh()
                }
            heart.contentDescription = "Избранное: ${track.title}"
            heart.setOnLongClickListener {
                actions.addToPlaylists(track)
                true
            }
            r.addView(heart, LinearLayout.LayoutParams(dp(48), dp(52)))
            list.addView(
                r,
                LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(3), 0, dp(3)) },
            )
        }
        if (lastPage > 0) {
            val navigation = row()
            navigation.addView(
                button("← Назад") {
                        actions.page(libraryPage - 1)
                        scrollView?.post { scrollView.scrollTo(0, 0) }
                    }
                    .apply { isEnabled = libraryPage > 0 },
                weight(),
            )
            navigation.addView(
                button("Далее →") {
                        actions.page(libraryPage + 1)
                        scrollView?.post { scrollView.scrollTo(0, 0) }
                    }
                    .apply { isEnabled = libraryPage < lastPage },
                weight(),
            )
            list.addView(navigation)
        }
        if (scrollY > 0) scrollView?.post { if (alive) scrollView.scrollTo(0, scrollY) }

        return Rendered(visible, trackLabels.toMap(), libraryPage)
    }
}
