package dev.alex.folderplayer

import android.os.Bundle
import androidx.media3.common.Player
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult

/**
 * Playback-service command handlers, split out of PlaybackService.applyPlaybackCommand
 * (roadmap 4.2). The dispatcher in PlaybackService bumps queueGeneration before
 * delegating, so every command below runs against a generation that invalidates
 * an in-flight async restore.
 */

internal fun PlaybackService.commandStartBook(args: Bundle): Int {
    val book = books.book(args.getString("bookId").orEmpty())
    if (book == null) return SessionError.ERROR_BAD_VALUE
    val mark = books.position(book)
    val requested = args.getString("uri")
    val restart = requested == null && mark.completed
    val uri = requested ?: if (restart) book.chapters.first().uri else mark.uri
    val index = book.chapters.indexOfFirst { it.uri == uri }
    if (index < 0) return SessionError.ERROR_BAD_VALUE
    savePosition()
    val chapter = book.chapters[index]
    val pos = (if (requested != null) args.getLong("position", 0) else if (restart) 0 else mark.position)
        .coerceAtLeast(0)
        .let { if (chapter.duration > 0) it.coerceAtMost(chapter.duration) else it }
    books.savePosition(book.id, uri, pos)
    prefs.edit().putString("activeBook", book.id).putString("queueSource", "Аудиокнига · ${book.title}").apply()
    player.setMediaItems(book.chapters.map { it.item(book) }, index, pos)
    player.repeatMode = Player.REPEAT_MODE_OFF; player.shuffleModeEnabled = false
    player.setPlaybackSpeed(books.speed(book.id)); player.setPauseAtEndOfMediaItems(false)
    player.prepare(); player.play()
    return SessionResult.RESULT_SUCCESS
}

internal fun PlaybackService.commandBookSpeed(args: Bundle): Int {
    val id = player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId")
    val speed = args.getFloat("speed", 1f)
    if (id == null || !speed.isFinite() || speed !in .5f..2.5f) return SessionError.ERROR_BAD_VALUE
    books.setSpeed(id, speed); player.setPlaybackSpeed(speed)
    return SessionResult.RESULT_SUCCESS
}

internal fun PlaybackService.commandStartOnlineQueue(args: Bundle): Int {
    val store = OnlineLibraryStore(this)
    val queueId = args.getString("queueId").orEmpty()
    val parsed = runCatching { OnlinePlayback.items(store.queue(queueId) ?: "[]") }.getOrNull()
    val mode = args.getString("mode", "play").orEmpty()
    val items = parsed?.excludingHidden(OnlineLibraryActions(this).ids("hidden")).orEmpty()
    val index = args.getInt("index", 0)
    if (parsed == null || mode !in listOf("play", "next", "append", "remove") || (mode != "remove" && (items.isEmpty() || (mode == "play" && index !in items.indices)))) return SessionError.ERROR_BAD_VALUE
    val old = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
    val combined = if (mode == "play") items else old + items
    if (mode != "remove" && (combined.size > 20000 || OnlineQueueMetadata.rows(combined).toString().length > 8_000_000)) return SessionError.ERROR_BAD_VALUE
    when (mode) {
        "play" -> {
            leaveBook()
            prefs.edit().putString("queueSource", afp(R.string.yandex_music) + store.queueSource(queueId).takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()).remove("onlineQueueId").remove("onlineQueue").apply()
            player.setMediaItems(items, index, 0); player.setPauseAtEndOfMediaItems(false); player.prepare(); player.play()
        }
        "next", "append" -> {
            if (player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") != null) return SessionError.ERROR_BAD_VALUE
            if (old.any { !it.mediaId.startsWith("afp-yandex://") }) prefs.edit().putString("queueSource", "Смешанная очередь").apply()
            else prefs.edit().putString("queueSource", afp(R.string.yandex_music)).apply()
            val at = if (mode == "next" && old.isNotEmpty()) (player.currentMediaItemIndex + 1).coerceAtMost(old.size) else old.size
            player.addMediaItems(at, items)
            if (old.isEmpty()) { player.prepare(); player.play() }
        }
        "remove" -> {
            val ids = parsed.map { it.mediaId }.toSet()
            for (i in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(i).mediaId in ids) player.removeMediaItem(i)
        }
    }
    saveState(); store.dropQueue(queueId)
    return SessionResult.RESULT_SUCCESS
}

internal fun PlaybackService.commandStartQueue(args: Bundle): Int {
    val library = Library(this)
    val source = args.getString("source").orEmpty()
    val queue =
        library.ordered(library.playableTracks(), source, prefs.getInt("librarySort", 0))
    val index = queue.indexOfFirst { it.uri == args.getString("uri") }
    if (index < 0) return SessionError.ERROR_BAD_VALUE
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
    return SessionResult.RESULT_SUCCESS
}

internal fun PlaybackService.commandReorderQueue(args: Bundle): Int {
    if (player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") != null) return SessionResult.RESULT_SUCCESS
    val source = args.getString("source").orEmpty()
    if (prefs.getString("queueSource", "") != source || player.mediaItemCount == 0)
        return SessionResult.RESULT_SUCCESS
    val library = Library(this)
    val old = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
    val byId = old.groupBy { it.mediaId }
    val refresh = args.getBoolean("refreshSource", false)
    val playable = library.playableTracks()
    val playableIds = playable.map { it.uri }.toSet()
    val known =
        playable.filter { refresh || it.uri in byId }.associateBy { it.uri }
    val sourceIds =
        library
            .ordered(known.values.toList(), source, prefs.getInt("librarySort", 0))
            .map { it.uri }
    val extraIds = old.map { it.mediaId }.filter { it !in sourceIds && it in known }
    val ordered =
        (sourceIds + extraIds).distinct().flatMap {
            byId[it]?.map { item -> if (refresh) known.getValue(it).item() else item } ?: listOf(known.getValue(it).item())
        } + old.filter { it.mediaId !in known && QueueEligibility.playable(it.mediaId, playableIds, OnlineLibraryActions(this).ids("hidden")) }
    val current = player.currentMediaItem?.mediaId
    val occurrence =
        old.take(player.currentMediaItemIndex.coerceAtLeast(0)).count {
            it.mediaId == current
        }
    val index =
        ordered.indices
            .filter { ordered[it].mediaId == current }
            .getOrNull(occurrence) ?: -1
    if (index >= 0 && player.playbackState != Player.STATE_ENDED) {
        val position = player.currentPosition
        val state = player.playbackState
        player.setMediaItems(ordered, index, position)
        if (state != Player.STATE_IDLE) player.prepare()
    }
    return SessionResult.RESULT_SUCCESS
}

internal fun PlaybackService.commandSetRepeat(args: Bundle): Int {
    if (player.currentMediaItem?.mediaMetadata?.extras?.getString("bookId") != null) return SessionError.ERROR_BAD_VALUE
    val mode = args.getInt("mode", -1)
    if (mode !in 0..2) return SessionError.ERROR_BAD_VALUE
    prefs.edit().putInt("requestedRepeat", mode).apply()
    player.repeatMode = mode
    return SessionResult.RESULT_SUCCESS
}
