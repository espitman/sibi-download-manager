package com.espitman.sdm.domain

import java.util.UUID
import java.net.URI

enum class DownloadState {
    QUEUED,
    CONNECTING,
    DOWNLOADING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class Download(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val mimeType: String? = null,
    val etag: String? = null,
    val lastModified: String? = null,
    val destinationPath: String? = null,
    val totalBytes: Long? = null,
    val downloadedBytes: Long = 0,
    val state: DownloadState = DownloadState.QUEUED,
    val error: String? = null,
    val priority: Int = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "Download ID cannot be blank" }
        require(url.isNotBlank()) { "Download URL cannot be blank" }
        require(runCatching { URI(url).scheme?.lowercase() in setOf("http", "https") }.getOrDefault(false)) {
            "Download URL must use HTTP or HTTPS"
        }
        require(fileName.isNotBlank()) { "Filename cannot be blank" }
        require('/' !in fileName && '\\' !in fileName) { "Filename cannot contain path separators" }
        require(mimeType == null || mimeType.isNotBlank()) { "MIME type cannot be blank" }
        require(etag == null || etag.isNotBlank()) { "ETag cannot be blank" }
        require(lastModified == null || lastModified.isNotBlank()) { "Last-Modified cannot be blank" }
        require(destinationPath == null || destinationPath.isNotBlank()) { "Destination path cannot be blank" }
        require(totalBytes == null || totalBytes >= 0) { "Total bytes cannot be negative" }
        require(downloadedBytes >= 0) { "Downloaded bytes cannot be negative" }
        require(priority >= 0) { "Priority cannot be negative" }
        require(totalBytes == null || downloadedBytes <= totalBytes) {
            "Downloaded bytes cannot exceed total bytes"
        }
        require(updatedAtEpochMillis >= createdAtEpochMillis) {
            "Updated time cannot precede creation time"
        }
        require(state != DownloadState.COMPLETED || totalBytes == null || downloadedBytes == totalBytes) {
            "A completed download must contain all known bytes"
        }
        require(state != DownloadState.FAILED || !error.isNullOrBlank()) {
            "A failed download must include an error"
        }
        require(state == DownloadState.FAILED || error == null) {
            "Only failed downloads may contain an error"
        }
        require(state != DownloadState.COMPLETED || completedAtEpochMillis != null) {
            "A completed download must include its completion time"
        }
    }
}
