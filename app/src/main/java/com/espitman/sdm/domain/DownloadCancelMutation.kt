package com.espitman.sdm.domain

import kotlin.math.max
import kotlin.math.min

object DownloadCancelMutation {
    private val CANCELLABLE = setOf(
        DownloadState.QUEUED,
        DownloadState.CONNECTING,
        DownloadState.DOWNLOADING,
        DownloadState.PAUSED,
        DownloadState.FAILED,
    )

    fun apply(
        current: Download,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download {
        if (current.state !in CANCELLABLE) return current
        require(fileLengthBytes >= 0L) { "Cancelled offset cannot be negative" }
        val boundedOffset = current.totalBytes?.let { min(fileLengthBytes, it) } ?: fileLengthBytes
        val timestamp = max(nowEpochMillis, current.updatedAtEpochMillis)
        return DownloadStateMachine.transition(
            current.copy(downloadedBytes = boundedOffset),
            DownloadState.CANCELLED,
            timestamp,
        )
    }
}
