package dev.alex.folderplayer

import android.app.AlertDialog
import android.view.View
import android.widget.*

/** Playlist dialogs & management: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.sortDialog() {
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

internal fun MainActivity.addFolderToPlaylist(name: String, onDone: () -> Unit = {}) {
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

internal fun MainActivity.bulkAdd() {
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

internal fun MainActivity.renamePlaylist() {
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

internal fun MainActivity.createPlaylist(after: (String) -> Unit) {
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

internal fun MainActivity.choosePlaylist() {
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

internal fun MainActivity.addToPlaylists(track: Track) {
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

internal fun MainActivity.playlistMenu() {
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

internal fun MainActivity.linkedPlaylistFolders(name: String) {
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

