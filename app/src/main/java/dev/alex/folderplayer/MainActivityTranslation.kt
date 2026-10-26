package dev.alex.folderplayer

import android.app.AlertDialog

/** Translation playback UI: extracted from MainActivity (roadmap 4.2). */
internal fun MainActivity.cancelTranslation() {
        translationWork.cancel()
        translationId = null
    }


internal fun MainActivity.translate(track: Track, force: Boolean = false, renew: Boolean = false) {
        if (!force && translationId == track.uri) return
        translationId = track.uri
        if (!force && translationWork.state().key == track.uri) {
            translationWork.attach { showTranslation(it) }
            return
        }
        val repository = SongTranslationRepository(applicationContext)
        translationWork.start(track.uri) { cancel, progress ->
            repository.translate(track, renew, cancel, progress)
        }
        translationWork.attach { showTranslation(it) }
    }

internal fun MainActivity.showTranslation(state: RetainedOperation.Snapshot<SongTranslationResult>) {
        val track = currentTrack() ?: return
        if (!alive || page != "translation" || state.key != track.uri) return
        if (state.busy) {
            screens.setTranslationProgress(track.uri, state.message)
            return
        }
        state.value?.let { result ->
            val message =
                if (result.incomplete > 0)
                    "Не удалось перевести фрагментов: ${result.incomplete}. Они отмечены в тексте; готовая часть сохранена на экране."
                else if (result.model == SongLanguage.RUSSIAN) "Оригинал уже на русском"
                else "Сохранено · " + TranslationClient.MODELS[result.model].orEmpty()
            screens.setTranslation(
                track.uri,
                result.text,
                message,
                if (result.incomplete > 0) 2 else 0,
            )
            return
        }
        state.failure?.let { failure ->
            if (failure is ChooseSongLanguage) {
                screens.setTranslation(
                    track.uri,
                    "",
                    "Не удалось уверенно определить язык. Выбери язык этой песни.",
                    2,
                )
                if (translationForeground) chooseSongLanguage(track, failure.source, failure.models)
            } else
                screens.setTranslationProgress(
                    track.uri,
                    failure.message ?: "Не удалось перевести",
                    2,
                )
        }
    }

internal fun MainActivity.renewTranslation(track: Track) {
        AlertDialog.Builder(this)
            .setTitle("Перевести заново?")
            .setMessage(
                "Сохранённый перевод этой песни будет заменён только после успешного завершения. Остальные переводы останутся."
            )
            .setPositiveButton("Перевести") { _, _ -> translate(track, true, true) }
            .setNegativeButton("Отмена", null)
            .show()
    }

internal fun MainActivity.chooseSongLanguage(
        track: Track,
        source: String,
        available: List<Pair<String, String>>,
    ) {
        val models = TranslationLanguages.choices(available)
        if (models.isEmpty()) {
            screens.setTranslation(
                track.uri,
                "",
                "Нет установленных языковых пакетов. Открой расширения.",
                1,
            )
            return
        }
        val choices = models.map { it.second } + "Определять автоматически"
        AlertDialog.Builder(this)
            .setTitle("Язык оригинала этой песни")
            .setItems(choices.toTypedArray()) { _, i ->
                if (currentTrack()?.uri != track.uri) return@setItems
                runCatching {
                        val saved = SongTranslationChoice(this)
                        if (i == models.size) saved.clear(track.uri, source)
                        else saved.save(track.uri, source, models[i].first)
                    }
                    .onSuccess { translate(track, true) }
                    .onFailure { toast("Не удалось сохранить выбор языка") }
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

internal fun MainActivity.editSongLanguage(track: Track) {
        runAsync({
            val source =
                loadLyrics(track)?.let { LyricsDocument(it).plain }
                    ?: error("Сначала добавь текст")
            source to translationClient.availableModels().toList()
        }) { result ->
            postUi {
                if (currentTrack()?.uri == track.uri)
                    result
                        .onSuccess { (source, models) ->
                            showPage("translation")
                            cancelTranslation()
                            translationId = track.uri
                            chooseSongLanguage(track, source, models)
                        }
                        .onFailure { toast(it.message ?: "Не удалось проверить языки") }
            }
        }
    }
