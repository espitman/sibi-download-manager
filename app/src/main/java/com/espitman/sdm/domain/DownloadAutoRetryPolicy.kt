package com.espitman.sdm.domain

object DownloadAutoRetryPolicy {
    const val MAX_AUTOMATIC_RETRIES = 2
    const val FIRST_RETRY_DELAY_MS = 1_000L
    const val SECOND_RETRY_DELAY_MS = 2_000L

    fun isAutomaticallyRetryable(failure: DownloadFailure): Boolean =
        failure == DownloadFailure.NETWORK_LOSS ||
            failure == DownloadFailure.TIMEOUT ||
            failure == DownloadFailure.TRANSIENT_HTTP

    fun delayBeforeAutomaticRetryMs(automaticRetryCount: Int): Long? =
        when (automaticRetryCount) {
            0 -> FIRST_RETRY_DELAY_MS
            1 -> SECOND_RETRY_DELAY_MS
            else -> null
        }

    fun shouldAutomaticallyRetry(download: Download): Boolean {
        if (download.state != DownloadState.FAILED) return false
        if (delayBeforeAutomaticRetryMs(download.automaticRetryCount) == null) return false
        return isAutomaticallyRetryable(DownloadFailure.classify(download.error))
    }
}
