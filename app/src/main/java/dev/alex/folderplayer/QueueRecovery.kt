package dev.alex.folderplayer

/** Original indices preserve duplicate tracks when unavailable entries are removed. */
internal object QueueRecovery {
    data class Position(val index: Int, val milliseconds: Long)

    fun position(
        original: List<String>, retainedIndices: List<Int>, index: Int, milliseconds: Long,
        checkpointIndex: Int, checkpointUri: String?, checkpointPosition: Long,
    ): Position {
        if (retainedIndices.isEmpty()) return Position(0, 0)
        val checkpointMatches = checkpointIndex in original.indices &&
            original[checkpointIndex] == checkpointUri
        val target = if (checkpointMatches) checkpointIndex else index.coerceIn(0, original.lastIndex)
        val retained = retainedIndices.indexOf(target)
        if (retained >= 0) return Position(retained,
            (if (checkpointMatches) checkpointPosition else milliseconds).coerceAtLeast(0))
        // The active entry disappeared: resume at the next surviving entry, from its start.
        val next = retainedIndices.indexOfFirst { it > target }
        return Position(if (next >= 0) next else retainedIndices.lastIndex, 0)
    }
}
