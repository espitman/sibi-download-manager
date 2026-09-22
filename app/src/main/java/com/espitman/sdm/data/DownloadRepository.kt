package com.espitman.sdm.data

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.PauseQueuedMutation
import com.espitman.sdm.domain.RecoverInterruptedActiveMutation
import com.espitman.sdm.domain.RequeueNetworkPausedMutation
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
    /**
     * Lowers [Download.downloadedBytes] to match a shorter on-disk partial after the
     * file was deleted or truncated outside the app. Never increases progress.
     * Implementations must persist the aligned record; completed rows stay unchanged.
     *
     * There is no no-op default. Computing alignment here and returning the current
     * row when offsets already match would let an alternate repository compile and
     * pass ordinary transfers, then crash only when a deleted or truncated `.part`
     * actually needed a write.
     */
    suspend fun alignDownloadedBytes(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download = error("alignDownloadedBytes is not implemented")
    suspend fun pauseAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long): Download?
    suspend fun pauseAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
        pauseCause: DownloadPauseCause?,
    ): Download? = pauseAtExactOffset(id, fileLengthBytes, nowEpochMillis)
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
    suspend fun pauseQueuedPreservingOffsets(nowEpochMillis: Long): List<Download> =
        pauseQueuedPreservingOffsets(nowEpochMillis, pauseCause = null)

    suspend fun pauseQueuedPreservingOffsets(
        nowEpochMillis: Long,
        pauseCause: DownloadPauseCause?,
    ): List<Download> {
        awaitInitialized()
        val updated = ArrayList<Download>()
        for (download in downloads.value) {
            val next = PauseQueuedMutation.apply(download, nowEpochMillis, pauseCause) ?: continue
            updated += transition(download.id, DownloadState.PAUSED, next.updatedAtEpochMillis)
        }
        return updated
    }

    /**
     * Re-queues a stale CONNECTING/DOWNLOADING record after process or device
     * interruption without recording a failure or consuming the automatic retry budget.
     * Missing and non-active records return null and leave stored state unchanged.
     */
    suspend fun requeueInterruptedActive(
        id: String,
        nowEpochMillis: Long,
    ): Download? {
        val current = get(id) ?: return null
        val next = RecoverInterruptedActiveMutation.apply(current, nowEpochMillis) ?: return null
        return transition(id, DownloadState.QUEUED, next.updatedAtEpochMillis)
    }

    /**
     * Re-queues only records paused by network policy. Manual pauses stay paused.
     */
    suspend fun requeueNetworkPolicyPaused(nowEpochMillis: Long): List<Download> {
        awaitInitialized()
        val updated = ArrayList<Download>()
        for (download in downloads.value) {
            val next = RequeueNetworkPausedMutation.apply(download, nowEpochMillis) ?: continue
            resumePaused(download.id, next.updatedAtEpochMillis)?.let(updated::add)
        }
        return updated
    }

    /**
     * Pause a queued or active record for network policy without recording a failure.
     * Preserves the exact downloaded offset for resumable partial bytes.
     */
    suspend fun pauseRecordForNetworkPolicy(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download? {
        val current = get(id) ?: return null
        return when (current.state) {
            DownloadState.QUEUED -> {
                pauseQueuedPreservingOffsets(nowEpochMillis, DownloadPauseCause.NETWORK_POLICY)
                get(id)
            }
            DownloadState.CONNECTING, DownloadState.DOWNLOADING ->
                pauseAtExactOffset(
                    id,
                    fileLengthBytes,
                    nowEpochMillis,
                    DownloadPauseCause.NETWORK_POLICY,
                )
            else -> current
        }
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

    /**
     * Updates the finalized destination after a local transfer when a user-selected
     * folder is published, or after a safe fallback to app-private storage.
     */
    suspend fun updateDestination(
        id: String,
        destinationPath: String,
        destinationTreeUri: String?,
        destinationDisplayLabel: String?,
        fileName: String,
        nowEpochMillis: Long,
    ): Download = error("updateDestination is not implemented")

    /** Bytes newly transferred on the local calendar day containing [nowEpochMillis] in [zoneId]. */
    suspend fun transferredBytesForLocalDay(
        nowEpochMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
    ): Long = 0L
}
