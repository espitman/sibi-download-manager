package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadMoveToTopMutationTest {
    @Test
    fun nonQueuedRecordsAreUnchanged() {
        val paused = queued("a", sortOrder = 2).copy(state = DownloadState.PAUSED)
        assertEquals(emptyList<Download>(), DownloadMoveToTopMutation.apply(paused, listOf(paused), 9_000))
    }

    @Test
    fun alreadyFirstQueuedRecordIsUnchanged() {
        val first = queued("a", sortOrder = 0, createdAt = 10)
        val second = queued("b", sortOrder = 0, createdAt = 20)
        assertEquals(emptyList<Download>(), DownloadMoveToTopMutation.apply(first, listOf(second, first), 9_000))
    }

    @Test
    fun assignsOneLessThanMinimumSortOrderInTheSamePriorityTier() {
        val first = queued("a", sortOrder = -3, createdAt = 10)
        val second = queued("b", sortOrder = 4, createdAt = 1)
        val otherPriority = queued("high", priority = 1, sortOrder = -100, createdAt = 1)
        val pausedPeer = queued("paused", sortOrder = -50).copy(state = DownloadState.PAUSED)

        val updated = DownloadMoveToTopMutation.apply(
            second,
            listOf(first, second, otherPriority, pausedPeer),
            nowEpochMillis = 500,
        )

        assertEquals(1, updated.size)
        assertEquals(-4L, updated.single().sortOrder)
        assertEquals(1_000L, updated.single().updatedAtEpochMillis)
        assertEquals(0, updated.single().priority)
        assertEquals(DownloadState.QUEUED, updated.single().state)
        assertEquals(0L, updated.single().downloadedBytes)
    }

    @Test
    fun rebasesFromLongMinValueWithoutUnderflowAndPreservesRelativeOrder() {
        val first = queued("first", sortOrder = Long.MIN_VALUE, createdAt = 30)
        val second = queued("second", sortOrder = Long.MIN_VALUE, createdAt = 40)
        val third = queued("third", sortOrder = 9, createdAt = 10)

        val updated = DownloadMoveToTopMutation.apply(
            third,
            listOf(second, first, third),
            nowEpochMillis = 2_000,
        )

        assertEquals(listOf("third", "first", "second"), updated.map { it.id })
        assertEquals(listOf(0L, 1L, 2L), updated.map { it.sortOrder })
        assertTrue(updated.all { it.updatedAtEpochMillis == 2_000L })
        assertTrue(updated.all { it.priority == 0 })
        assertEquals(
            listOf("third", "first", "second"),
            updated.sortedWith(DownloadQueueOrder.comparator).map { it.id },
        )
    }

    private fun queued(
        id: String,
        priority: Int = 0,
        sortOrder: Long = 0,
        createdAt: Long = 1_000,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        priority = priority,
        sortOrder = sortOrder,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = 1_000,
    )
}
