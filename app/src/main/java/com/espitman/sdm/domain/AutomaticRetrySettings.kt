package com.espitman.sdm.domain

/** Zero attempts disables automatic retries. The first request is not counted as a retry. */
data class AutomaticRetrySettings(val maxRetries: Int = 2, val delaySeconds: Int = 2) {
    init {
        require(maxRetries in 0..10)
        require(delaySeconds in DELAYS)
    }
    companion object {
        val DELAYS: List<Int> = (1..9).toList() + (10..180 step 10).toList()
        fun normalizeDelay(value: Int): Int = DELAYS.minBy { kotlin.math.abs(it.toLong() - value.toLong()) }
    }
    fun dueAt(download: Download): Long? {
        if (download.state != DownloadState.FAILED || download.automaticRetryCount >= maxRetries ||
            !DownloadAutoRetryPolicy.isAutomaticallyRetryable(DownloadFailure.classify(download.error))) return null
        val failureAt = download.failedAtEpochMillis ?: download.updatedAtEpochMillis
        return failureAt + delaySeconds * 1_000L
    }
}
