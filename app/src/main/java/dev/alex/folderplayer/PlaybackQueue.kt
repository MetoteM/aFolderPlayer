package dev.alex.folderplayer

/** The playback queue comes from its source, never from the search results. */
internal fun playbackTracks(
    tracks: List<Track>,
    playlist: Collection<String>?,
    sort: Int,
    playlistSort: Int = SORT_MANUAL,
): List<Track> {
    if (playlist != null) {
        val byUri = tracks.associateBy { it.uri }
        return playlistOrder(playlist.mapNotNull { byUri[it] }, playlistSort)
    }
    return when (sort) {
        1 ->
            tracks.sortedWith { a, b ->
                val artist = naturalCompare(a.artist, b.artist)
                if (artist != 0) artist else naturalCompare(a.title, b.title)
            }
        2 ->
            tracks.sortedWith { a, b ->
                val folder = naturalCompare(a.folder, b.folder)
                if (folder != 0) folder
                else naturalCompare(a.filename.ifBlank { a.title }, b.filename.ifBlank { b.title }).takeIf { it!=0 } ?: a.cueStartMs.compareTo(b.cueStartMs)
            }
        else -> tracks.sortedWith { a, b -> naturalCompare(a.title, b.title) }
    }
}

internal object PlaybackCommands {
    const val START_ONLINE_QUEUE = "dev.alex.folderplayer.START_ONLINE_QUEUE"
    const val START_QUEUE = "dev.alex.folderplayer.START_QUEUE"
    const val REORDER_QUEUE = "dev.alex.folderplayer.REORDER_QUEUE"
    const val SET_REPEAT = "dev.alex.folderplayer.SET_REPEAT"
}

/** Source-aware eligibility: local library exclusions must not remove online items. */
internal object QueueEligibility {
    fun playable(id:String,localPlayable:Set<String>,onlineHidden:Set<String>):Boolean {
        val online=OnlinePlayback.trackId(android.net.Uri.parse(id))
        return if(online!=null)online !in onlineHidden else id in localPlayable
    }
}
