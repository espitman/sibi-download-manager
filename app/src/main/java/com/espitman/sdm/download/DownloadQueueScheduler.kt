package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.max

fun interface ConcurrentDownloadLimit {
    fun maxConcurrent(): Int
}

fun interface QueuedTransferStarter {
    fun startQueued(download: Download)
}

class DownloadQueueScheduler(
    private val repository: DownloadRepository,
    private val concurrentLimit: ConcurrentDownloadLimit,
    private val starter: QueuedTransferStarter,
    private val clock: Clock = Clock.SystemClock,
    private val transferAllowance: TransferAllowance = TransferAllowance { true },
) {
    private val mutex = Mutex()
    private val bulkMutex = Mutex()
    private val launchingIds = linkedSetOf<String>()

    suspend fun schedule() {
        val failedThisPass = mutableSetOf<String>()
        while (true) {
            val selected = mutex.withLock { claimLocked(failedThisPass) }
            if (selected.isEmpty()) return
            var anyStarted = false
            for (download in selected) {
                try {
                    starter.startQueued(download)
                    anyStarted = true
                } catch (_: Throwable) {
                    failedThisPass.add(download.id)
                    mutex.withLock { launchingIds.remove(download.id) }
                }
            }
            if (!anyStarted) continue
        }
    }

    suspend fun downloadAll() {
        bulkMutex.withLock {
            repository.awaitInitialized()
            repository.requeueForDownloadAll(clock.currentTimeMillis())
            schedule()
        }
    }

    suspend fun pauseAll(pauseActive: (String) -> Unit) {
        bulkMutex.withLock {
            repository.awaitInitialized()
            val activeIds = mutex.withLock {
                repository.pauseQueuedPreservingOffsets(clock.currentTimeMillis())
                val snapshot = repository.schedulingSnapshot()
                pruneLaunchingLocked(snapshot)
                snapshot
                    .filter { it.state in DownloadQueuePolicy.OCCUPYING_STATES }
                    .map { it.id }
            }
            for (id in activeIds) {
                pauseActive(id)
            }
        }
    }

    suspend fun resume(downloadId: String) {
        repository.awaitInitialized()
        val current = repository.get(downloadId) ?: return
        val nowEpochMillis = max(clock.currentTimeMillis(), current.updatedAtEpochMillis)
        when (current.state) {
            DownloadState.PAUSED -> repository.resumePaused(id = downloadId, nowEpochMillis = nowEpochMillis)
            DownloadState.CANCELLED -> repository.resumeCancelled(id = downloadId, nowEpochMillis = nowEpochMillis)
            DownloadState.FAILED -> repository.retryFailed(
                id = downloadId,
                automatic = false,
                nowEpochMillis = nowEpochMillis,
            )
            else -> return
        }
        schedule()
    }

    suspend fun startQueued(downloadId: String) {
        repository.awaitInitialized()
        val current = repository.get(downloadId) ?: return
        if (current.state != DownloadState.QUEUED) return
        repository.moveToTop(
            id = downloadId,
            nowEpochMillis = max(clock.currentTimeMillis(), current.updatedAtEpochMillis),
        )
        schedule()
    }

    suspend fun togglePriority(downloadId: String): Download? {
        repository.awaitInitialized()
        val current = repository.get(downloadId) ?: return null
        val updated = repository.togglePriority(
            id = downloadId,
            nowEpochMillis = max(clock.currentTimeMillis(), current.updatedAtEpochMillis),
        )
        schedule()
        return updated
    }

    suspend fun releaseClaim(downloadId: String) {
        mutex.withLock { launchingIds.remove(downloadId) }
    }

    suspend fun releaseSlot(downloadId: String) {
        releaseClaim(downloadId)
        schedule()
    }

    private suspend fun claimLocked(excludeIds: Set<String>): List<Download> {
        repository.awaitInitialized()
        if (!transferAllowance.isAllowed()) return emptyList()
        val snapshot = repository.schedulingSnapshot()
        pruneLaunchingLocked(snapshot)
        val selected = DownloadQueuePolicy.select(
            downloads = snapshot,
            maxConcurrent = concurrentLimit.maxConcurrent().coerceIn(1, 10),
            extraOccupiedIds = launchingIds,
            excludeIds = excludeIds,
        )
        launchingIds.addAll(selected.map { it.id })
        return selected
    }

    private fun pruneLaunchingLocked(snapshot: List<Download>) {
        val byId = snapshot.associateBy { it.id }
        launchingIds.removeAll { id ->
            val state = byId[id]?.state
            state == null || (state != DownloadState.QUEUED && state !in DownloadQueuePolicy.OCCUPYING_STATES)
        }
    }
}
