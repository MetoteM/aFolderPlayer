package dev.alex.folderplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C

/** PlayerScreens.Actions implementation: extracted from onCreate (roadmap 4.2). */
internal fun MainActivity.buildActions(): PlayerScreens.Actions {
    val activity = this
    return object : PlayerScreens.Actions {
                    override fun back() {
                        backPage()
                    }

                    override fun open(page: String) {
                        if (page == "lyrics") openLyrics() else showPage(page)
                    }

                    override fun toggle() {
                        togglePlay()
                        updatePlayer()
                    }

                    override fun previous() {
                        controller?.seekToPreviousMediaItem()
                    }

                    override fun next() {
                        controller?.seekToNextMediaItem()
                    }

                    override fun skip(direction: Int) {
                        this.skip(direction)
                    }

                    override fun seek(progress: Int) {
                        controller?.let {
                            if (it.duration > 0 && it.duration != C.TIME_UNSET)
                                it.seekTo(it.duration * progress / 1000)
                        }
                    }

                    override fun favorite() {
                        currentTrack()?.let {
                            val item=controller?.currentMediaItem
                            val online=item?.let { media -> OnlineQueueMetadata.row(media) }
                            if(online!=null) {
                                val actions=OnlineLibraryActions(activity)
                                actions.set("favorites",org.json.JSONArray().put(online),!actions.contains("favorites",online.optString("id")))
                            } else library.toggleFavorite(it)
                            render()
                            updatePlayer()
                        }
                    }

                    override fun playlists() {
                        currentTrack()?.let { addToPlaylists(it) }
                    }

                    override fun repeat() {
                        repeatDialog()
                    }

                    override fun sound() {
                        equalizer()
                    }

                    override fun recognize() {
                        recognitionMenu()
                    }

                    override fun installTranslationModel() {
                        extensions()
                    }

                    override fun documentTranslation() {
                        val track = currentTrack()
                        cancelTranslation()
                        translationId = track?.uri
                        runAsync(
                            {
                                track?.let { loadLyrics(it) }?.let { LyricsDocument(it).plain }
                                    ?: ""
                            },
                        ) { result ->
                            result
                                .onSuccess {
                                    if (track != null && currentTrack()?.uri == track.uri)
                                        startActivityForResult(
                                            Intent(
                                                    activity,
                                                    SongTextActivity::class.java,
                                                )
                                                .putExtra("source", it)
                                                .putExtra("songTitle", track.title)
                                                .putExtra("artist", track.artist)
                                                .putExtra("localUri", track.uri)
                                                .putExtra("openTranslation", true),
                                            107,
                                        )
                                }
                                .onFailure {
                                    toast(it.message ?: "Не удалось прочитать текст")
                                }
                        }
                    }

                    override fun retryTranslation() {
                        currentTrack()?.let { translate(it, true) }
                    }

                    override fun renewTranslation() {
                        currentTrack()?.let { renewTranslation(it) }
                    }

                    override fun songLanguage() {
                        currentTrack()?.let { editSongLanguage(it) }
                    }

                    override fun retryLyrics() {
                        currentTrack()?.let { directLyrics(it) }
                    }

                    override fun chooseLyrics(index: Int) {
                        val track = currentTrack()
                        val result = directResults.getOrNull(index)
                        if (track != null && track.uri == directId && result != null)
                            saveDirectLyrics(track, result)
                    }

                    override fun lyricsMenu() {
                        currentTrack()?.let { currentSongMenu(it) } ?: toast("Сначала включи песню")
                    }

                    override fun select(index: Int) {
                        controller?.let {
                            if (index in 0 until it.mediaItemCount) {
                                it.seekTo(index, 0)
                                it.play()
                            }
                        }
                    }

                    override fun move(from: Int, to: Int) {
                        controller?.let {
                            if (
                                from in 0 until it.mediaItemCount && to in 0 until it.mediaItemCount
                            ) {
                                it.moveMediaItem(from, to)
                                queueTimeline = null
                                updatePlayer()
                            }
                        }
                    }
    }
}
