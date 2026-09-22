package com.espitman.sdm.domain

import kotlin.math.max

object DownloadRenameMutation {
    private val ACTIVE = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)

    fun apply(
        current: Download,
        fileName: String,
        destinationPath: String,
        nowEpochMillis: Long,
    ): Download {
        require(current.state !in ACTIVE) { "Pause the download before renaming" }
        require(fileName.isNotBlank()) { "Filename cannot be blank" }
        require('/' !in fileName && '\\' !in fileName) { "Filename cannot contain path separators" }
        require(destinationPath.isNotBlank()) { "Destination path cannot be blank" }
        if (current.fileName == fileName && current.destinationPath == destinationPath) return current
        return current.copy(
            fileName = fileName,
            destinationPath = destinationPath,
            updatedAtEpochMillis = max(nowEpochMillis, current.updatedAtEpochMillis),
        )
    }
}
