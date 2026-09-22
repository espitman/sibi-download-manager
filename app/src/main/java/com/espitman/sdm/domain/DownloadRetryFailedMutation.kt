package com.espitman.sdm.domain

import kotlin.math.max

object DownloadRetryFailedMutation {
    fun apply(current: Download, automatic: Boolean, nowEpochMillis: Long): Download? {
        if (current.state != DownloadState.FAILED) return null
        val nextCount = if (automatic) {
            require(current.automaticRetryCount < Int.MAX_VALUE) { "Automatic retry count overflow" }
            current.automaticRetryCount + 1
        } else {
            0
        }
        return DownloadStateMachine.transition(
            current,
            DownloadState.QUEUED,
            max(nowEpochMillis, current.updatedAtEpochMillis),
        ).copy(automaticRetryCount = nextCount)
    }
}
