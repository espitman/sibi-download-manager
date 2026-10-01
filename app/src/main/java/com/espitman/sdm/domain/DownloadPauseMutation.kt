package com.espitman.sdm.domain

import kotlin.math.max
import kotlin.math.min

object DownloadPauseMutation {
    private val ACTIVE = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)

    fun apply(
        current: Download,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
        pauseCause: DownloadPauseCause? = null,
    ): Download {
        if (current.state == DownloadState.FAILED && pauseCause == null &&
            DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.classify(current.error))) return DownloadStateMachine.transition(
            current, DownloadState.PAUSED, max(nowEpochMillis, current.updatedAtEpochMillis),
        ).copy(pauseCause = pauseCause)
        if (current.state !in ACTIVE) return current
        require(fileLengthBytes >= 0L) { "Paused offset cannot be negative" }
        val boundedOffset = current.totalBytes?.let { min(fileLengthBytes, it) } ?: fileLengthBytes
        val timestamp = max(nowEpochMillis, current.updatedAtEpochMillis)
        return DownloadStateMachine.transition(
            current.copy(downloadedBytes = boundedOffset),
            DownloadState.PAUSED,
            timestamp,
        ).copy(pauseCause = pauseCause)
    }
}
