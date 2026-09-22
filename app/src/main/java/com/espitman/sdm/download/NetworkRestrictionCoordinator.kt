package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadPauseCause
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class NetworkRestrictionCoordinator(
    private val repository: DownloadRepository,
    private val wifiOnly: () -> Boolean,
    private val connectivity: () -> ValidatedConnectivity,
    private val allowance: MutableTransferAllowance,
    private val scheduler: DownloadQueueScheduler,
    private val pauseActive: (String) -> Unit,
    private val clock: Clock = Clock.SystemClock,
) {
    private val mutex = Mutex()

    fun allowsTransfers(): Boolean = WifiOnlyPolicy.allowsTransfers(wifiOnly(), connectivity())

    fun syncAllowanceFromSnapshot() {
        allowance.setAllowed(allowsTransfers())
    }

    suspend fun apply() = mutex.withLock {
        applyLocked()
    }

    private suspend fun applyLocked() {
        val allowed = allowsTransfers()
        allowance.setAllowed(allowed)
        repository.awaitInitialized()
        if (allowed) {
            repository.requeueNetworkPolicyPaused(clock.currentTimeMillis())
            scheduler.schedule()
        } else {
            repository.pauseQueuedPreservingOffsets(
                clock.currentTimeMillis(),
                DownloadPauseCause.NETWORK_POLICY,
            )
            val activeIds = repository.schedulingSnapshot()
                .filter { it.state in DownloadQueuePolicy.OCCUPYING_STATES }
                .map { it.id }
            for (id in activeIds) {
                pauseActive(id)
            }
        }
    }
}

object NetworkRestrictionStartGuard {
    suspend fun blockStartIfDisallowed(
        allowed: Boolean,
        repository: DownloadRepository,
        downloadId: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Boolean {
        if (allowed) return false
        repository.pauseRecordForNetworkPolicy(downloadId, fileLengthBytes, nowEpochMillis)
        return true
    }
}
