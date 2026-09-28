package com.espitman.sdm.domain

import kotlin.math.max

/** Reorders unfinished work while keeping the scheduler's priority-tier ordering intact. */
object DownloadQueueReorderMutation {
    fun apply(
        queued: List<Download>,
        sourceId: String,
        targetId: String,
        placeAfter: Boolean,
        nowEpochMillis: Long,
    ): List<Download> {
        val ordered = queued.filter { it.state != DownloadState.COMPLETED }
            .sortedWith(DownloadQueueOrder.comparator)
        val source = ordered.firstOrNull { it.id == sourceId } ?: return emptyList()
        val target = ordered.firstOrNull { it.id == targetId } ?: return emptyList()
        if (sourceId == targetId) return emptyList()

        val moved = source.copy(priority = target.priority)
        val next = ordered.filterNot { it.id == sourceId }.toMutableList()
        val targetIndex = next.indexOfFirst { it.id == targetId }
        next.add(targetIndex + if (placeAfter) 1 else 0, moved)
        if (next.map { it.id } == ordered.map { it.id } && source.priority == moved.priority) return emptyList()

        // Rebase every unfinished tier to avoid sort-order collisions or Long overflow.
        return next.groupBy { it.priority }.values.flatMap { tier ->
            tier.mapIndexed { index, download ->
                download.copy(
                    sortOrder = index.toLong(),
                    updatedAtEpochMillis = max(nowEpochMillis, download.updatedAtEpochMillis),
                )
            }
        }
    }
}
