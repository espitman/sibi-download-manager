package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.domain.DownloadState

/** Boot receivers may restore records and alarms, but must not launch dataSync directly. */
object BootScheduleRecovery {
    suspend fun restore(
        repository: DownloadRepository,
        autoResume: Boolean,
        clock: Clock,
        armTick: (Long?) -> Unit,
    ) {
        DownloadInterruptionRecovery.recover(
            repository = repository,
            clock = clock,
            trigger = DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = autoResume,
        )
        armTick(nextTick(repository.schedulingSnapshot(), clock.currentTimeMillis()))
    }

    fun nextTick(downloads: List<Download>, now: Long): Long? {
        val nextBoundary = downloads.asSequence()
            .filter { it.state !in setOf(DownloadState.COMPLETED, DownloadState.CANCELLED, DownloadState.FAILED) &&
                (it.state != DownloadState.PAUSED || it.pauseCause == DownloadPauseCause.SCHEDULE) }
            .mapNotNull { it.schedule?.nextBoundary(now) }
            .minOrNull()
        val runnable = downloads.any { download ->
            (download.state == DownloadState.QUEUED &&
                download.schedule?.isOpen(now) != false) ||
                download.state == DownloadState.PAUSED &&
                download.pauseCause == DownloadPauseCause.SCHEDULE &&
                download.schedule?.isOpen(now) == true
        }
        return if (runnable) minOf(nextBoundary ?: Long.MAX_VALUE, now + 5_000L)
        else nextBoundary
    }
}
