package com.espitman.sdm.download

import com.espitman.sdm.domain.DownloadState

/**
 * Whether the transfer service should hold a PARTIAL_WAKE_LOCK.
 *
 * Evaluated only while [DownloadTransferService] is alive; queue-duration
 * coverage is therefore limited to the service lifetime until a later
 * scheduler can keep the service running for queued work.
 */
object KeepActivePolicy {
    /** Safety timeout for [android.os.PowerManager.WakeLock.acquire]. */
    const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L

    const val DURATION_DOWNLOADING = "downloading"
    const val DURATION_QUEUE = "queue"

    fun shouldHoldWakeLock(
        keepActive: Boolean,
        keepActiveDuration: String,
        states: Iterable<DownloadState>,
    ): Boolean {
        if (!keepActive) return false
        return when (keepActiveDuration) {
            DURATION_QUEUE -> states.any { it.isQueueDurationActive }
            DURATION_DOWNLOADING -> states.any { it.isDownloadingDurationActive }
            else -> false
        }
    }

    private val DownloadState.isDownloadingDurationActive: Boolean
        get() = this == DownloadState.CONNECTING || this == DownloadState.DOWNLOADING

    private val DownloadState.isQueueDurationActive: Boolean
        get() = this == DownloadState.QUEUED || isDownloadingDurationActive
}
