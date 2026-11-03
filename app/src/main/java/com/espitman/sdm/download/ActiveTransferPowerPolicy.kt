package com.espitman.sdm.download

import com.espitman.sdm.domain.DownloadState

/** Automatic CPU protection for real transfers, independent of the retired keep-active setting. */
object ActiveTransferPowerPolicy {
    const val WAKE_LOCK_TIMEOUT_MS = 10L * 60L * 1_000L

    fun shouldHoldWakeLock(states: Iterable<DownloadState>): Boolean = states.any {
        it == DownloadState.CONNECTING || it == DownloadState.DOWNLOADING
    }
}
