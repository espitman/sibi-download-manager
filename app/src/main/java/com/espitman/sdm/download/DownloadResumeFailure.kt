package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import kotlin.math.max

object DownloadResumeFailure {
    suspend fun persist(
        repository: DownloadRepository,
        downloadId: String,
        error: String,
        nowEpochMillis: Long,
    ) {
        var current = repository.get(downloadId) ?: return
        fun timestamp(): Long = max(nowEpochMillis, current.updatedAtEpochMillis)
        if (current.state == DownloadState.PAUSED) {
            current = repository.resumePaused(downloadId, timestamp()) ?: return
        }
        if (current.state == DownloadState.QUEUED) {
            current = repository.transition(
                id = downloadId,
                to = DownloadState.CONNECTING,
                nowEpochMillis = timestamp(),
            )
        }
        if (current.state == DownloadState.CONNECTING || current.state == DownloadState.DOWNLOADING) {
            repository.transition(
                id = downloadId,
                to = DownloadState.FAILED,
                nowEpochMillis = timestamp(),
                error = error,
            )
        }
    }
}
