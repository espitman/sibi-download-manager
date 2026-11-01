package com.espitman.sdm.domain

import kotlin.math.max

/** Reorders unfinished work while keeping the scheduler's priority-tier ordering intact. */
object DownloadQueueReorderMutation {
    fun groupToTop(records: List<Download>, sourceIds: Set<String>, now: Long): List<Download> = records
        .filter { it.state != DownloadState.COMPLETED }.sortedWith(DownloadQueueOrder.comparator)
        .groupBy { it.priority }.values.flatMap { tier ->
            val next = tier.filter { it.id in sourceIds } + tier.filterNot { it.id in sourceIds }
            if (next.map { it.id } == tier.map { it.id }) emptyList()
            else next.mapIndexed { index, record -> record.copy(sortOrder=index.toLong(), updatedAtEpochMillis=max(now,record.updatedAtEpochMillis)) }
        }

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

        // Only the target tier needs rebasing; unrelated tiers retain exact metadata.
        return next.groupBy { it.priority }.values.flatMap { tier ->
            if (tier.first().priority != target.priority) tier else tier.mapIndexed { index, download ->
                if (download.sortOrder == index.toLong() && ordered.first { it.id == download.id }.priority == download.priority) download else download.copy(
                    sortOrder = index.toLong(),
                    updatedAtEpochMillis = max(nowEpochMillis, download.updatedAtEpochMillis),
                )
            }
        }
    }
}
