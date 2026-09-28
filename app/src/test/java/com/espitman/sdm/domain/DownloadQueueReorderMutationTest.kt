package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadQueueReorderMutationTest {
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

    @Test fun ignoresNonQueuedAndNoOp() {
        val queue = listOf(row("a", 0), row("b", 1))
        assertTrue(DownloadQueueReorderMutation.apply(queue, "a", "a", true, 100).isEmpty())
        assertTrue(DownloadQueueReorderMutation.apply(queue, "missing", "b", true, 100).isEmpty())
        assertTrue(DownloadQueueReorderMutation.apply(queue + row("paused", 2).copy(state = DownloadState.PAUSED), "paused", "b", true, 100).isEmpty())
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
