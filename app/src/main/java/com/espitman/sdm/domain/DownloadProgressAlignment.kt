package com.espitman.sdm.domain

import kotlin.math.max
import kotlin.math.min

object DownloadProgressAlignment {
    fun apply(current: Download, fileLengthBytes: Long, nowEpochMillis: Long): Download {
        require(fileLengthBytes >= 0L) { "Aligned offset cannot be negative" }
        if (current.state == DownloadState.COMPLETED) return current
        val bounded = current.totalBytes?.let { min(fileLengthBytes, it) } ?: fileLengthBytes
        if (bounded >= current.downloadedBytes) return current
        return current.copy(
            downloadedBytes = bounded,
            updatedAtEpochMillis = max(nowEpochMillis, current.updatedAtEpochMillis),
        )
    }
}
