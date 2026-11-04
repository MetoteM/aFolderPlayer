package dev.alex.folderplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueRecoveryTest {
    private val queue = listOf("a", "b", "c", "b")
    private fun recover(retained: List<Int>, index: Int = 2, checkpoint: Int = -1,
                        uri: String? = null, position: Long = 7000) =
        QueueRecovery.position(queue, retained, index, 4000, checkpoint, uri, position)

    @Test fun removedEarlierEntryKeepsCurrentTrackAndPosition() {
        assertEquals(QueueRecovery.Position(1, 4000), recover(listOf(1, 2, 3)))
    }
    @Test fun latestCheckpointSurvivesIndexShift() {
        assertEquals(QueueRecovery.Position(1, 7000), recover(listOf(1, 2, 3), checkpoint = 2, uri = "c"))
    }
    @Test fun duplicateTrackKeepsItsOriginalOccurrence() {
        assertEquals(QueueRecovery.Position(2, 7000), recover(listOf(1, 2, 3), checkpoint = 3, uri = "b"))
    }
    @Test fun removedCurrentTrackStartsNextAtZero() {
        assertEquals(QueueRecovery.Position(1, 0), recover(listOf(0, 3)))
    }
    @Test fun removedLastTrackStartsLastSurvivorAtZero() {
        assertEquals(QueueRecovery.Position(1, 0), recover(listOf(0, 1), index = 3))
    }
    @Test fun staleCheckpointCannotSeekAnotherTrack() {
        assertEquals(QueueRecovery.Position(2, 4000), recover(listOf(0, 1, 2, 3), checkpoint = 2, uri = "a"))
    }
    @Test fun emptyQueueHasNoPosition() {
        assertEquals(QueueRecovery.Position(0, 0), recover(emptyList()))
    }
    @Test fun negativeCheckpointPositionIsClamped() {
        assertEquals(QueueRecovery.Position(1, 0), recover(listOf(1, 2), checkpoint = 2, uri = "c", position = -5))
    }
}
