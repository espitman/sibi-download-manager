package com.espitman.sdm.domain

import kotlin.math.max

object RecoverInterruptedActiveMutation {
    private val ACTIVE = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)

    /**
     * Re-queues a stale CONNECTING/DOWNLOADING record after process or device
     * interruption without recording a failure or consuming the automatic retry budget.
     */
    fun apply(current: Download, nowEpochMillis: Long): Download? {
        if (current.state !in ACTIVE) return null
        return DownloadStateMachine.transition(
            current,
            DownloadState.QUEUED,
            max(nowEpochMillis, current.updatedAtEpochMillis),
        )
    }
}
