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
        return applyGroup(queued, setOf(sourceId), targetId, placeAfter, nowEpochMillis)
    }

    fun applyGroup(
        queued: List<Download>, sourceIds: Set<String>, targetId: String,
        placeAfter: Boolean, nowEpochMillis: Long,
    ): List<Download> {
        val ordered = queued.filter { it.state != DownloadState.COMPLETED }
            .sortedWith(DownloadQueueOrder.comparator)
        val sources = ordered.filter { it.id in sourceIds }
        val target = ordered.firstOrNull { it.id == targetId } ?: return emptyList()
        if (sources.isEmpty() || targetId in sourceIds) return emptyList()
        val next = ordered.filterNot { it.id in sourceIds }.toMutableList()
        val targetIndex = next.indexOfFirst { it.id == targetId }
        next.addAll(targetIndex + if (placeAfter) 1 else 0, sources.map { it.copy(priority = target.priority) })
        if (next.map { it.id to it.priority } == ordered.map { it.id to it.priority }) return emptyList()

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
