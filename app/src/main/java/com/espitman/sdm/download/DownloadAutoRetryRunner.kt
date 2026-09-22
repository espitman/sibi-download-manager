package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadAutoRetryPolicy
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.max

class DownloadAutoRetryRunner(
    private val repository: DownloadRepository,
    private val clock: Clock = Clock.SystemClock,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun run(downloadId: String, transfer: suspend () -> Unit) {
        while (true) {
            currentCoroutineContext().ensureActive()
            transfer()
            currentCoroutineContext().ensureActive()
            val record = repository.get(downloadId) ?: return
            if (!DownloadAutoRetryPolicy.shouldAutomaticallyRetry(record)) return
            val waitMs = DownloadAutoRetryPolicy.delayBeforeAutomaticRetryMs(record.automaticRetryCount)
                ?: return
            delayMillis(waitMs)
            currentCoroutineContext().ensureActive()
            val nowEpochMillis = max(clock.currentTimeMillis(), record.updatedAtEpochMillis)
            repository.retryFailed(
                id = downloadId,
                automatic = true,
                nowEpochMillis = nowEpochMillis,
            ) ?: return
        }
    }
}
