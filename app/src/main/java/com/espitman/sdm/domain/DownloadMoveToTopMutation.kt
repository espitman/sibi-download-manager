package com.espitman.sdm.domain

import kotlin.math.max

object DownloadMoveToTopMutation {
    fun apply(
        current: Download,
        queuedSamePriority: List<Download>,
        nowEpochMillis: Long,
    ): List<Download> {
        if (current.state != DownloadState.QUEUED) return emptyList()
        val peers = queuedSamePriority
            .filter { it.state == DownloadState.QUEUED && it.priority == current.priority }
            .let { list -> if (list.any { it.id == current.id }) list else list + current }
        val ordered = peers.sortedWith(DownloadQueueOrder.comparator)
        if (ordered.firstOrNull()?.id == current.id) return emptyList()

        val minSort = peers.minOf { it.sortOrder }
        if (minSort > Long.MIN_VALUE) {
            val timestamp = max(nowEpochMillis, current.updatedAtEpochMillis)
            return listOf(current.copy(sortOrder = minSort - 1L, updatedAtEpochMillis = timestamp))
        }

        val rest = peers.filter { it.id != current.id }.sortedWith(DownloadQueueOrder.comparator)
        return (listOf(current) + rest).mapIndexed { index, download ->
            download.copy(
                sortOrder = index.toLong(),
                updatedAtEpochMillis = max(nowEpochMillis, download.updatedAtEpochMillis),
            )
        }
    }
}
