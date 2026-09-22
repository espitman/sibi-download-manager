package com.espitman.sdm.domain

object DownloadQueueOrder {
    val comparator: Comparator<Download> =
        compareByDescending<Download> { it.priority }
            .thenBy { it.sortOrder }
            .thenBy { it.createdAtEpochMillis }
            .thenBy { it.id }
}
