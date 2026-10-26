package dev.alex.folderplayer

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem

/** Metadata only: load on a worker, apply to ExoPlayer on its application looper. */
internal data class PlaybackRestore(
    val items: List<MediaItem>,
    val index: Int,
    val position: Long,
    val book: Audiobook?,
) {
    companion object {
        fun load(context: Context): PlaybackRestore {
            val prefs = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
            val positionPrefs = context.getSharedPreferences("playback-position", Context.MODE_PRIVATE)
            val books = Audiobooks(context)
            val queue = Library.strings(prefs.getString("queue", "[]")!!)
            val book = books.book(prefs.getString("activeBook", "").orEmpty())
            val bookItems = book?.chapters?.associate { it.uri to it.item(book) }.orEmpty()
            val local = if (queue.any { it !in bookItems && !it.startsWith("afp-yandex://") })
                Library(context).playableTracks().associateBy { it.uri } else emptyMap()
            val online = if (queue.any { it.startsWith("afp-yandex://") }) {
                val store = OnlineLibraryStore(context)
                val hidden = OnlineLibraryActions(context).ids("hidden")
                runCatching {
                    OnlinePlayback.items(store.read("playing")?.optJSONArray("tracks")?.toString()
                        ?: store.queue(prefs.getString("onlineQueueId", "").orEmpty())
                        ?: prefs.getString("onlineQueue", "[]")!!)
                }.getOrDefault(emptyList())
                    .filter { OnlinePlayback.trackId(Uri.parse(it.mediaId)) !in hidden }
                    .associateBy { it.mediaId }
            } else emptyMap()
            val items = queue.mapNotNull { bookItems[it] ?: local[it]?.item() ?: online[it] }
            var index = prefs.getInt("index", 0)
            var position = prefs.getLong("position", 0)
            val checkpoint = positionPrefs.getInt("index", -1)
            if (checkpoint in items.indices && items[checkpoint].mediaId == positionPrefs.getString("uri", null)) {
                index = checkpoint
                position = positionPrefs.getLong("position", position).coerceAtLeast(0)
            }
            if (book != null) {
                val mark = books.position(book)
                val chapter = items.indexOfFirst { it.mediaId == mark.uri }
                if (chapter >= 0) { index = chapter; position = mark.position }
            }
            return PlaybackRestore(items, index, position, book)
        }
    }
}
