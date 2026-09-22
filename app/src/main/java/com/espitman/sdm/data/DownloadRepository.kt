package com.espitman.sdm.data

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.PauseQueuedMutation
import kotlinx.coroutines.flow.StateFlow

interface DownloadRepository {
    val downloads: StateFlow<List<Download>>

    suspend fun awaitInitialized()
    suspend fun get(id: String): Download?
    /** Consistent snapshot for scheduler selection; serialized with writes when the implementation supports it. */
    suspend fun schedulingSnapshot(): List<Download> {
        awaitInitialized()
        return downloads.value
    }
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
    /** Atomically re-queue PAUSED/FAILED/CANCELLED records; progress and partial files are preserved. */
    suspend fun requeueForDownloadAll(nowEpochMillis: Long): List<Download> {
        awaitInitialized()
        val updated = ArrayList<Download>()
        for (download in downloads.value) {
            val next = DownloadAllMutation.apply(download, nowEpochMillis) ?: continue
            when (download.state) {
                DownloadState.PAUSED -> resumePaused(download.id, next.updatedAtEpochMillis)?.let(updated::add)
                DownloadState.FAILED, DownloadState.CANCELLED ->
                    updated += transition(download.id, DownloadState.QUEUED, next.updatedAtEpochMillis)
                else -> Unit
            }
        }
        return updated
    }
    /** Atomically move currently QUEUED records to PAUSED, preserving exact downloaded offsets. */
    suspend fun pauseQueuedPreservingOffsets(nowEpochMillis: Long): List<Download> {
        awaitInitialized()
        val updated = ArrayList<Download>()
        for (download in downloads.value) {
            val next = PauseQueuedMutation.apply(download, nowEpochMillis) ?: continue
            updated += transition(download.id, DownloadState.PAUSED, next.updatedAtEpochMillis)
        }
        return updated
    }
    suspend fun togglePriority(id: String, nowEpochMillis: Long): Download?
    suspend fun beginFreshRestart(
        id: String,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download
}
