package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadQueueOrder
import com.espitman.sdm.domain.DownloadState

object DownloadQueuePolicy {
    val OCCUPYING_STATES = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)

    fun occupyingIds(
        downloads: List<Download>,
        extraOccupiedIds: Set<String> = emptySet(),
    ): Set<String> {
        val byId = downloads.associateBy { it.id }
        return buildSet {
            downloads.forEach { download ->
                if (download.state in OCCUPYING_STATES) add(download.id)
            }
            extraOccupiedIds.forEach { id ->
                val state = byId[id]?.state ?: return@forEach
                if (state == DownloadState.QUEUED || state in OCCUPYING_STATES) add(id)
            }
        }
    }

    fun select(
        downloads: List<Download>,
        maxConcurrent: Int,
        extraOccupiedIds: Set<String> = emptySet(),
        excludeIds: Set<String> = emptySet(),
    ): List<Download> {
        val limit = maxConcurrent.coerceAtLeast(0)
        val occupying = occupyingIds(downloads, extraOccupiedIds)
        val freeSlots = (limit - occupying.size).coerceAtLeast(0)
        if (freeSlots == 0) return emptyList()
        return downloads
            .filter { it.state == DownloadState.QUEUED && it.id !in occupying && it.id !in excludeIds }
            .sortedWith(queueOrder)
            .take(freeSlots)
    }

    val queueOrder: Comparator<Download> = DownloadQueueOrder.comparator
}
