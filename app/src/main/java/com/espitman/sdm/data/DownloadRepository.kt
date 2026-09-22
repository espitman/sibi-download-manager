package com.espitman.sdm.data

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.PauseQueuedMutation
import java.time.ZoneId
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
    /**
     * Re-queues a FAILED record, preserving downloaded bytes and destination path.
     * Automatic retries increment [Download.automaticRetryCount] once; manual retries reset it to 0.
     * Missing and non-failed records return null and leave stored state unchanged.
     */
    suspend fun retryFailed(
        id: String,
        automatic: Boolean,
        nowEpochMillis: Long,
    ): Download? = null
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
    /** Moves a QUEUED record first within its priority tier via a lower sortOrder; no-op if missing, non-queued, or already first. */
    suspend fun moveToTop(id: String, nowEpochMillis: Long): Download? = null
    /** Atomically updates fileName and destinationPath for a non-active record. */
    suspend fun renameRecord(
        id: String,
        fileName: String,
        destinationPath: String,
        nowEpochMillis: Long,
    ): Download = error("renameRecord is not implemented")
    suspend fun beginFreshRestart(
        id: String,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download

    /** Bytes newly transferred on the local calendar day containing [nowEpochMillis] in [zoneId]. */
    suspend fun transferredBytesForLocalDay(
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long = 0L
}
