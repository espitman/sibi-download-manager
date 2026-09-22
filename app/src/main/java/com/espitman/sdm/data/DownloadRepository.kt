package com.espitman.sdm.data

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val downloads: StateFlow<List<Download>>

    suspend fun awaitInitialized()
    suspend fun get(id: String): Download?
    suspend fun insert(download: Download)
    suspend fun delete(id: String): Boolean
    suspend fun transition(
        id: String,
        to: DownloadState,
        nowEpochMillis: Long,
        error: String? = null,
    ): Download
    suspend fun updateProgress(id: String, downloadedBytes: Long, nowEpochMillis: Long): Download
    suspend fun pauseAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long): Download?
    suspend fun cancelAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long): Download?
    suspend fun resumePaused(id: String, nowEpochMillis: Long): Download?
    suspend fun beginFreshRestart(
        id: String,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download
}
