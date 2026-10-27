package dev.alex.folderplayer

import android.content.Intent
import android.net.Uri

/** Activity-result routing: legacy codes (103/104/107/REQUEST) + helpers for the Activity Result API launchers. */
internal fun MainActivity.handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ExternalExtensions.REQUEST && resultCode == android.app.Activity.RESULT_OK) {
            data?.data?.let { importExtensionFolder(it) }
            return
        }
        if (requestCode == 107) {
            if (page == "translation") currentTrack()?.let { translate(it, true) }
            return
        }
        if (requestCode == 104 && resultCode == android.app.Activity.RESULT_OK)
            data?.data?.let { extensionManager.importEngine(it) }
        if (requestCode == 103 && resultCode == android.app.Activity.RESULT_OK)
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
}

/** Lyric-text import from a SAF document (launcher lyricsImporter, ex-requestCode 101). */
internal fun MainActivity.importLyricsFrom(uri: android.net.Uri) {
        val target = lyricTarget ?: return
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

/** Lyric-text export to a SAF document (launcher lyricsExporter, ex-requestCode 102). */
internal fun MainActivity.exportLyricsTo(uri: android.net.Uri) {
        val target = lyricTarget ?: return
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
