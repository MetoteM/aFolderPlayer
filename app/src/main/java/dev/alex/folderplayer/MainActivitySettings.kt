package dev.alex.folderplayer

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.widget.*

/** Settings, folders, scanning, recognition & extension screens: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.recognitionMenu() {
        val track =
            currentTrack()
                ?: run {
                    toast(afp(R.string.pick_song_first))
                    return
                }
        AlertDialog.Builder(this)
            .setTitle(afp(R.string.recognize_song))
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
            .setNegativeButton(afp(R.string.cancel), null)
            .show()
    }

internal fun MainActivity.identifySong() {
        controller?.pause()
        startActivity(Intent(this, RecognitionActivity::class.java))
    }

internal fun MainActivity.importExtensionFolder(uri: Uri) {
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

internal fun MainActivity.settings() {
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

internal fun MainActivity.folders() {
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
                    .setNegativeButton(afp(R.string.cancel), null)
                    .show()
            }
            .setNegativeButton(afp(R.string.close), null)
            .show()
    }

internal fun MainActivity.scan() {
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


internal fun MainActivity.extensions() {
        extensionManager.show()
    }


internal fun MainActivity.about() {
        AlertDialog.Builder(this)
            .setTitle("aFolderPlayer · ${appUpdates.installedVersion()}")
            .setMessage(
                "Музыка по папкам, тексты песен и перевод. Без рекламы и аналитики.\n\nИнтернет используется только по твоему запросу. Перевод работает на телефоне после установки языкового пакета."
            )
            .setPositiveButton(afp(R.string.close), null)
            .setNeutralButton("Проверить обновления") { _, _ -> appUpdates.check() }
            .setNegativeButton("Подробнее") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("Сервисы и лицензии")
                    .setMessage(
                        "Поиск текстов: LRCLIB. Годы альбомов: MusicBrainz. Обновления: GitHub. Распознавание: неофициальный интерфейс Shazam; отправляется звуковой отпечаток, запись и геолокация не отправляются.\n\nMedia3 / AndroidX: Apache 2.0. Языковые пакеты: см. лицензии пакетов. ONNX Runtime: MIT. Алгоритм распознавания адаптирован из ShazamIO 0.4.0.0, MIT, © 2021 dotX12.\n\nРазработка при участии ChatGPT."
                    )
                    .setPositiveButton(afp(R.string.close), null)
                    .show()
            }
            .show()
    }

