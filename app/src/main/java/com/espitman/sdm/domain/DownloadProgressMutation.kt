package com.espitman.sdm.domain

/** The retry budget belongs to consecutive failures without successful byte progress. */
object DownloadProgressMutation {
    fun apply(current: Download, downloadedBytes: Long, nowEpochMillis: Long): Download {
        require(current.state == DownloadState.DOWNLOADING) { "Progress can only change while downloading" }
        require(downloadedBytes >= current.downloadedBytes) { "Download progress cannot move backwards" }
        require(nowEpochMillis >= current.updatedAtEpochMillis) { "Progress time cannot move backwards" }
        return current.copy(
            downloadedBytes = downloadedBytes,
            updatedAtEpochMillis = nowEpochMillis,
            automaticRetryCount = if (downloadedBytes > current.downloadedBytes) 0 else current.automaticRetryCount,
        )
    }
}
