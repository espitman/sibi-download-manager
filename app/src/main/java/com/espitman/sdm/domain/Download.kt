package com.espitman.sdm.domain

import java.util.UUID

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
    val sortOrder: Long = 0,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val startedAtEpochMillis: Long? = null,
    val completedAtEpochMillis: Long? = null,
    /**
     * Resume-capability evidence from the origin server.
     * `null` means unavailable (legacy rows or unknown), `false` means the server did not
     * advertise byte ranges, and `true` means it did.
     */
    val acceptsRanges: Boolean? = null,
    /**
     * Origin-advertised SHA-256 of the complete object, normalized to 64 lowercase hex.
     * `null` means unavailable (legacy rows or no valid checksum header).
     */
    val referenceSha256: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Download ID cannot be blank" }
        require(url.isNotBlank()) { "Download URL cannot be blank" }
        require(DownloadUrl.validate(url) is DownloadUrlResult.Valid) {
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
        require(referenceSha256 == null || isNormalizedReferenceSha256(referenceSha256)) {
            "Reference SHA-256 must be 64 lowercase hexadecimal characters"
        }
    }
}

private fun isNormalizedReferenceSha256(value: String): Boolean {
    if (value.length != 64) return false
    return value.all { ch -> ch in '0'..'9' || ch in 'a'..'f' }
}
