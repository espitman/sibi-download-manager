package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import kotlin.math.max

enum class DownloadInterruptionTrigger(val errorMessage: String) {
    PROCESS_RESTART("Interrupted when the app process stopped"),
    DEVICE_BOOT("Interrupted when the device restarted"),
}

object DownloadInterruptionRecovery {
    private val ACTIVE_STATES = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)

    suspend fun recover(
        repository: DownloadRepository,
        clock: Clock,
        trigger: DownloadInterruptionTrigger,
    ) {
        repository.awaitInitialized()
        val snapshotIds = repository.downloads.value
            .filter { it.state in ACTIVE_STATES }
            .map { it.id }
        for (id in snapshotIds) {
            val current = repository.get(id) ?: continue
            if (current.state !in ACTIVE_STATES) continue
            val nowEpochMillis = max(clock.currentTimeMillis(), current.updatedAtEpochMillis)
            try {
                repository.transition(
                    id = id,
                    to = DownloadState.FAILED,
                    nowEpochMillis = nowEpochMillis,
                    error = trigger.errorMessage,
                )
            } catch (thrown: Throwable) {
                val latest = repository.get(id)
                if (latest == null || latest.state !in ACTIVE_STATES) continue
                throw thrown
            }
        }
    }
}
