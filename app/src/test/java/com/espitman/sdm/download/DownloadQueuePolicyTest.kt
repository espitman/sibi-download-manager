package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueuePolicyTest {
    @Test
    fun selectsHigherPriorityBeforeEarlierCreation() {
        val lowEarly = queued("low-early", priority = 0, createdAt = 1)
        val highLate = queued("high-late", priority = 5, createdAt = 9)
        val mid = queued("mid", priority = 1, createdAt = 2)

        val selected = DownloadQueuePolicy.select(
            downloads = listOf(lowEarly, highLate, mid),
            maxConcurrent = 2,
        )

        assertEquals(listOf("high-late", "mid"), selected.map { it.id })
    }

    @Test
    fun usesCreationThenIdForStableQueueOrder() {
        val later = queued("b", priority = 1, createdAt = 20)
        val earlier = queued("a", priority = 1, createdAt = 10)
        val sameTimeZ = queued("z", priority = 1, createdAt = 10)
        val sameTimeM = queued("m", priority = 1, createdAt = 10)

        val selected = DownloadQueuePolicy.select(
            downloads = listOf(later, earlier, sameTimeZ, sameTimeM),
            maxConcurrent = 4,
        )

        assertEquals(listOf("a", "m", "z", "b"), selected.map { it.id })
    }

    @Test
    fun neverExceedsRemainingCapacityAndSkipsOccupyingStates() {
        val queuedItems = listOf(
            queued("q1", priority = 3, createdAt = 1),
            queued("q2", priority = 2, createdAt = 2),
            queued("q3", priority = 1, createdAt = 3),
        )
        val occupying = listOf(
            queued("active-a", createdAt = 0).copy(state = DownloadState.CONNECTING),
            queued("active-b", createdAt = 0).copy(state = DownloadState.DOWNLOADING),
        )
        val ignored = listOf(
            queued("paused", createdAt = 0).copy(state = DownloadState.PAUSED),
            queued("failed", createdAt = 0).copy(state = DownloadState.FAILED, error = "x"),
            queued("done", createdAt = 0).copy(
                state = DownloadState.COMPLETED,
                completedAtEpochMillis = 1,
            ),
            queued("cancelled", createdAt = 0).copy(state = DownloadState.CANCELLED),
        )

        val selected = DownloadQueuePolicy.select(
            downloads = occupying + ignored + queuedItems,
            maxConcurrent = 4,
            extraOccupiedIds = setOf("q1"),
        )

        assertEquals(listOf("q2"), selected.map { it.id })
        assertTrue(selected.none { it.state != DownloadState.QUEUED })
    }

    @Test
    fun excludeIdsAreSkippedWithoutConsumingCapacity() {
        val selected = DownloadQueuePolicy.select(
            downloads = listOf(
                queued("boom", priority = 9, createdAt = 1),
                queued("ok", priority = 0, createdAt = 2),
            ),
            maxConcurrent = 1,
            excludeIds = setOf("boom"),
        )
        assertEquals(listOf("ok"), selected.map { it.id })
    }

    @Test
    fun returnsNothingWhenAtCapacity() {
        val selected = DownloadQueuePolicy.select(
            downloads = listOf(
                queued("active").copy(state = DownloadState.DOWNLOADING),
                queued("waiting", priority = 9),
            ),
            maxConcurrent = 1,
        )
        assertEquals(emptyList<Download>(), selected)
    }

    @Test
    fun cancelledLaunchingIdsDoNotConsumeCapacity() {
        val selected = DownloadQueuePolicy.select(
            downloads = listOf(
                queued("claimed").copy(state = DownloadState.CANCELLED),
                queued("next", priority = 0, createdAt = 2),
            ),
            maxConcurrent = 1,
            extraOccupiedIds = setOf("claimed"),
        )
        assertEquals(listOf("next"), selected.map { it.id })
    }

    private fun queued(
        id: String,
        priority: Int = 0,
        createdAt: Long = 1_000L,
    ) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        destinationPath = "/tmp/$id.bin",
        priority = priority,
        createdAtEpochMillis = createdAt,
    )
}
