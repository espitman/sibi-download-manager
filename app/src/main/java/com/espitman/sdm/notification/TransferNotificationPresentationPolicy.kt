package com.espitman.sdm.notification

import com.espitman.sdm.domain.DownloadState

object TransferNotificationPresentationPolicy {
    fun belongsToForegroundGroup(state: DownloadState): Boolean = when (state) {
        DownloadState.CONNECTING, DownloadState.DOWNLOADING -> true
        DownloadState.PAUSED,
        DownloadState.QUEUED,
        DownloadState.COMPLETED,
        DownloadState.FAILED,
        DownloadState.CANCELLED,
        -> false
    }
}
