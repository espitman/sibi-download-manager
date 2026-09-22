package com.espitman.sdm.domain

import kotlin.math.max

object DownloadFreshRestartMutation {
    fun apply(
        current: Download,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download {
        require(current.state == DownloadState.CONNECTING || current.state == DownloadState.DOWNLOADING) {
            "Fresh restart can only begin while active"
        }
        totalBytes?.let { require(it >= 0L) { "Restart total bytes cannot be negative" } }
        val cleanEtag = etag?.trim()?.takeIf { it.isNotEmpty() }
        val cleanLastModified = lastModified?.trim()?.takeIf { it.isNotEmpty() }
        return current.copy(
            etag = cleanEtag,
            lastModified = cleanLastModified,
            totalBytes = totalBytes,
            downloadedBytes = 0L,
            error = null,
            updatedAtEpochMillis = max(nowEpochMillis, current.updatedAtEpochMillis),
            completedAtEpochMillis = null,
        )
    }
}
