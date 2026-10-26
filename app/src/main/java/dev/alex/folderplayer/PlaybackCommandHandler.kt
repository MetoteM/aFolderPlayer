package dev.alex.folderplayer

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

/** Playback-only command handlers. All player mutations run on its application looper. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PlaybackCommandHandler(
    private val context: Context,
    private val player: ExoPlayer,
    private val prefs: SharedPreferences,
    private val books: Audiobooks,
    private val savePosition: () -> Unit,
    private val saveState: () -> Unit,
    private val cancelRestore: () -> Unit,
) {
    fun apply(action: String, args: Bundle): Int {
        val result =
            when (action) {
                PlaybackCommands.START_BOOK -> startBook(args)
                PlaybackCommands.BOOK_SPEED -> bookSpeed(args)
                PlaybackCommands.START_ONLINE_QUEUE -> startOnlineQueue(args)
                PlaybackCommands.START_QUEUE -> startQueue(args)
                PlaybackCommands.REORDER_QUEUE -> reorderQueue(args)
                PlaybackCommands.SET_REPEAT -> setRepeat(args)
                else -> SessionError.ERROR_NOT_SUPPORTED
            }
        if (
            result == SessionResult.RESULT_SUCCESS &&
                action in
                    setOf(
                        PlaybackCommands.START_BOOK,
                        PlaybackCommands.START_QUEUE,
                        PlaybackCommands.START_ONLINE_QUEUE,
                    )
        ) {
            cancelRestore()
            saveState()
        }
        return result
    }

    private fun startBook(args: Bundle): Int {
        return run {
            val book = books.book(args.getString("bookId").orEmpty())
            if (book == null) SessionError.ERROR_BAD_VALUE
            else {
                val mark = books.position(book)
                val requested = args.getString("uri")
                val restart = requested == null && mark.completed
                val uri = requested ?: if (restart) book.chapters.first().uri else mark.uri
                val index = book.chapters.indexOfFirst { it.uri == uri }
                if (index < 0) SessionError.ERROR_BAD_VALUE
                else {
                    savePosition()
                    val chapter = book.chapters[index]
                    val pos =
                        (if (requested != null) args.getLong("position", 0)
                            else if (restart) 0 else mark.position)
                            .coerceAtLeast(0)
                            .let {
                                if (chapter.duration > 0) it.coerceAtMost(chapter.duration) else it
                            }
                    books.savePosition(book.id, uri, pos)
                    prefs
                        .edit()
                        .putString("activeBook", book.id)
                        .putString("queueSource", "Аудиокнига · ${book.title}")
                        .apply()
                    player.setMediaItems(book.chapters.map { it.item(book) }, index, pos)
                    player.repeatMode = Player.REPEAT_MODE_OFF
                    player.shuffleModeEnabled = false
                    player.setPlaybackSpeed(books.speed(book.id))
                    player.setPauseAtEndOfMediaItems(false)
                    player.prepare()
                    player.play()
                    SessionResult.RESULT_SUCCESS
                }
            }
        }
    }

    private fun bookSpeed(args: Bundle): Int {
        return run {
            val id = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
            val speed = args.getFloat("speed", 1f)
            if (id == null || !speed.isFinite() || speed !in .5f..2.5f) SessionError.ERROR_BAD_VALUE
            else {
                books.setSpeed(id, speed)
                player.setPlaybackSpeed(speed)
                SessionResult.RESULT_SUCCESS
            }
        }
    }

    private fun startOnlineQueue(args: Bundle): Int {
        return run {
            val store = OnlineLibraryStore(context)
            val queueId = args.getString("queueId").orEmpty()
            val parsed =
                runCatching { OnlinePlayback.items(store.queue(queueId) ?: "[]") }.getOrNull()
            val mode = args.getString("mode", "play").orEmpty()
            val hidden = OnlineLibraryActions(context).ids("hidden")
            val items =
                parsed
                    ?.filter {
                        OnlinePlayback.trackId(android.net.Uri.parse(it.mediaId)) !in hidden
                    }
                    .orEmpty()
            val index = args.getInt("index", 0)
            if (
                parsed == null ||
                    mode !in listOf("play", "next", "append", "remove") ||
                    (mode != "remove" &&
                        (items.isEmpty() || (mode == "play" && index !in items.indices)))
            )
                SessionError.ERROR_BAD_VALUE
            else {
                val old = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val combined = if (mode == "play") items else old + items
                if (
                    mode != "remove" &&
                        (combined.size > 20000 ||
                            OnlineQueueMetadata.rows(combined).toString().length > 8_000_000)
                )
                    SessionError.ERROR_BAD_VALUE
                else {
                    when (mode) {
                        "play" -> {
                            leaveBook()
                            prefs
                                .edit()
                                .putString(
                                    "queueSource",
                                    "Яндекс Музыка" +
                                        store
                                            .queueSource(queueId)
                                            .takeIf { it.isNotBlank() }
                                            ?.let { " · $it" }
                                            .orEmpty(),
                                )
                                .remove("onlineQueueId")
                                .remove("onlineQueue")
                                .apply()
                            player.setMediaItems(items, index, 0)
                            player.setPauseAtEndOfMediaItems(false)
                            player.prepare()
                            player.play()
                        }
                        "next",
                        "append" -> {
                            if (
                                player.currentMediaItem
                                    ?.mediaMetadata
                                    ?.extras
                                    ?.getString("bookId") != null
                            )
                                return SessionError.ERROR_BAD_VALUE
                            if (old.any { !it.mediaId.startsWith("afp-yandex://") })
                                prefs.edit().putString("queueSource", "Смешанная очередь").apply()
                            else prefs.edit().putString("queueSource", "Яндекс Музыка").apply()
                            val at =
                                if (mode == "next" && old.isNotEmpty())
                                    (player.currentMediaItemIndex + 1).coerceAtMost(old.size)
                                else old.size
                            player.addMediaItems(at, items)
                            if (old.isEmpty()) {
                                player.prepare()
                                player.play()
                            }
                        }
                        "remove" -> {
                            val ids = parsed.map { it.mediaId }.toSet()
                            for (i in player.mediaItemCount - 1 downTo 0) if (
                                player.getMediaItemAt(i).mediaId in ids
                            )
                                player.removeMediaItem(i)
                        }
                    }
                    saveState()
                    store.dropQueue(queueId)
                    SessionResult.RESULT_SUCCESS
                }
            }
        }
    }

    private fun startQueue(args: Bundle): Int {
        return run {
            val library = Library(context)
            val source = args.getString("source").orEmpty()
            val queue =
                library.ordered(library.playableTracks(), source, prefs.getInt("librarySort", 0))
            val index = queue.indexOfFirst { it.uri == args.getString("uri") }
            if (index < 0) SessionError.ERROR_BAD_VALUE
            else {
                leaveBook()
                prefs.edit().putString("queueSource", source).apply()
                // Queue, starting item and repeat mode are applied together on the service
                // looper.
                player.setMediaItems(queue.map { it.item() }, index, 0)
                player.repeatMode =
                    prefs.getInt("requestedRepeat", player.repeatMode).coerceIn(0, 2)
                player.setPauseAtEndOfMediaItems(false)
                player.prepare()
                player.play()
                SessionResult.RESULT_SUCCESS
            }
        }
    }

    private fun reorderQueue(args: Bundle): Int {
        return run {
            if (player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") != null)
                return SessionResult.RESULT_SUCCESS
            val source = args.getString("source").orEmpty()
            if (prefs.getString("queueSource", "") != source || player.mediaItemCount == 0)
                SessionResult.RESULT_SUCCESS
            else {
                val library = Library(context)
                val old = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                val byId = old.groupBy { it.mediaId }
                val refresh = args.getBoolean("refreshSource", false)
                val playable = library.playableTracks()
                val playableIds = playable.map { it.uri }.toSet()
                val known = playable.filter { refresh || it.uri in byId }.associateBy { it.uri }
                val sourceIds =
                    library
                        .ordered(known.values.toList(), source, prefs.getInt("librarySort", 0))
                        .map { it.uri }
                val extraIds = old.map { it.mediaId }.filter { it !in sourceIds && it in known }
                val ordered =
                    (sourceIds + extraIds).distinct().flatMap {
                        byId[it]?.map { item -> if (refresh) known.getValue(it).item() else item }
                            ?: listOf(known.getValue(it).item())
                    } +
                        old.filter {
                            it.mediaId !in known &&
                                QueueEligibility.playable(
                                    it.mediaId,
                                    playableIds,
                                    OnlineLibraryActions(context).ids("hidden"),
                                )
                        }
                val current = player.currentMediaItem?.mediaId
                val occurrence =
                    old.take(player.currentMediaItemIndex.coerceAtLeast(0)).count {
                        it.mediaId == current
                    }
                val index =
                    ordered.indices.filter { ordered[it].mediaId == current }.getOrNull(occurrence)
                        ?: -1
                if (index >= 0 && player.playbackState != Player.STATE_ENDED) {
                    val position = player.currentPosition
                    val state = player.playbackState
                    player.setMediaItems(ordered, index, position)
                    if (state != Player.STATE_IDLE) player.prepare()
                }
                SessionResult.RESULT_SUCCESS
            }
        }
    }

    private fun setRepeat(args: Bundle): Int {
        return run {
            if (player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") != null)
                return SessionError.ERROR_BAD_VALUE
            val mode = args.getInt("mode", -1)
            if (mode !in 0..2) SessionError.ERROR_BAD_VALUE
            else {
                prefs.edit().putInt("requestedRepeat", mode).apply()
                player.repeatMode = mode
                SessionResult.RESULT_SUCCESS
            }
        }
    }

    private fun leaveBook() {
        savePosition()
        prefs.edit().remove("activeBook").apply()
        player.setPlaybackSpeed(1f)
        player.repeatMode = prefs.getInt("requestedRepeat", Player.REPEAT_MODE_OFF).coerceIn(0, 2)
        player.shuffleModeEnabled = false
    }
}
