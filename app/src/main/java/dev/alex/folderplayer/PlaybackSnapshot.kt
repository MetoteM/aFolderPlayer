package dev.alex.folderplayer

/** Queue is persisted on timeline changes; moving position is a small independent snapshot. */
internal data class PlaybackSnapshot(
    val index: Int,
    val position: Long,
    val repeat: Int,
    val shuffle: Boolean,
)
