package com.espitman.sdm.domain

import com.espitman.sdm.network.DownloadMetadata

/** Filename alone is never evidence that existing bytes belong to the new object. */
object DownloadLinkRefresh {
    fun canPreserve(download: Download, metadata: DownloadMetadata): Boolean {
        if (download.downloadedBytes == 0L) return true
        if (!metadata.acceptsRanges || download.totalBytes == null || download.totalBytes != metadata.contentLength) return false
        val checksum = download.referenceSha256
        if (checksum != null && metadata.referenceSha256 != null) return checksum == metadata.referenceSha256
        val etag = download.etag
        return etag != null && com.espitman.sdm.download.HttpRangeResume.isStrongEtag(etag) && etag == metadata.etag
    }
    fun replacement(download: Download, metadata: DownloadMetadata, restart: Boolean, now: Long): Download {
        require(download.state !in setOf(DownloadState.COMPLETED, DownloadState.CONNECTING, DownloadState.DOWNLOADING)) {
            "Pause this download before replacing its link."
        }
        require(restart || canPreserve(download, metadata)) { "This file needs a confirmed restart." }
        return download.copy(url = metadata.url, mimeType = metadata.contentType, etag = metadata.etag,
            lastModified = metadata.lastModified, totalBytes = metadata.contentLength,
            acceptsRanges = metadata.acceptsRanges, referenceSha256 = metadata.referenceSha256,
            downloadedBytes = if (restart) 0 else download.downloadedBytes, state = DownloadState.PAUSED,
            error = null, automaticRetryCount = 0, pauseCause = null, completedAtEpochMillis = null,
            updatedAtEpochMillis = now)
    }
}
