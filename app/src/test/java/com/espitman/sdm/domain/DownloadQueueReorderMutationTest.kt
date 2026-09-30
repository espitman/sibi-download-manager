package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueReorderMutationTest {
    @Test fun movesNonAdjacentGroupPreservingQueueOrderAndProgress() {
        val queue = listOf(row("a", 0), row("b", 1), row("c", 2), row("d", 3))
        val next = DownloadQueueReorderMutation.applyGroup(queue, setOf("c", "a"), "d", true, 100)
        assertEquals(listOf("b", "d", "a", "c"), next.sortedWith(DownloadQueueOrder.comparator).map { it.id })
        assertTrue(next.all { it.state == DownloadState.QUEUED && it.downloadedBytes == 0L })
        val back = DownloadQueueReorderMutation.applyGroup(next, setOf("a", "c"), "b", false, 101)
        assertEquals(listOf("a", "c", "b", "d"), back.sortedWith(DownloadQueueOrder.comparator).map { it.id })
        assertTrue(DownloadQueueReorderMutation.applyGroup(queue, setOf("a", "c"), "a", true, 100).isEmpty())
    }
    @Test fun movesDownAndUpWithinQueue() {
        val queue = listOf(row("a", 0), row("b", 1), row("c", 2))
        val down = DownloadQueueReorderMutation.apply(queue, "a", "c", true, 100)
        assertEquals(listOf("b", "c", "a"), down.sortedWith(DownloadQueueOrder.comparator).map { it.id })
        val up = DownloadQueueReorderMutation.apply(down, "a", "b", false, 101)
        assertEquals(listOf("a", "b", "c"), up.sortedWith(DownloadQueueOrder.comparator).map { it.id })
    }

    @Test fun crossingPriorityTierAdoptsTargetTierAndKeepsExactOrder() {
        val queue = listOf(row("high", 0, 1), row("first", 0), row("second", 1))
        val next = DownloadQueueReorderMutation.apply(queue, "second", "high", false, 100)
        assertEquals(listOf("second", "high", "first"), next.sortedWith(DownloadQueueOrder.comparator).map { it.id })
        assertEquals(1, next.first { it.id == "second" }.priority)
        assertTrue(next.all { it.state == DownloadState.QUEUED })
    }

    @Test fun pausedAndFailedKeepTheirOrderForLaterResume() {
        val queue = listOf(
            row("queued", 0),
            row("paused", 1).copy(state = DownloadState.PAUSED),
            row("failed", 2).copy(state = DownloadState.FAILED, error = "Network lost"),
        )
        val next = DownloadQueueReorderMutation.apply(queue, "failed", "queued", false, 100)
        assertEquals(listOf("failed", "queued", "paused"), next.sortedWith(DownloadQueueOrder.comparator).map { it.id })
        assertEquals(DownloadState.FAILED, next.first { it.id == "failed" }.state)
    }

    @Test fun ignoresCompletedAndNoOp() {
        val queue = listOf(row("a", 0), row("b", 1))
        assertTrue(DownloadQueueReorderMutation.apply(queue, "a", "a", true, 100).isEmpty())
        assertTrue(DownloadQueueReorderMutation.apply(queue, "missing", "b", true, 100).isEmpty())
        assertTrue(DownloadQueueReorderMutation.apply(queue + row("done", 2).copy(state = DownloadState.COMPLETED, completedAtEpochMillis = 2), "done", "b", true, 100).isEmpty())
    }

    private fun row(id: String, order: Long, priority: Int = 0) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        priority = priority,
        sortOrder = order,
        createdAtEpochMillis = 1,
    )
}
