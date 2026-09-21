package com.espitman.sdm.domain

import kotlin.math.max

object DownloadResumeMutation {
    fun apply(current: Download, nowEpochMillis: Long): Download? {
        if (current.state != DownloadState.PAUSED) return null
        return DownloadStateMachine.transition(
            current,
            DownloadState.QUEUED,
            max(nowEpochMillis, current.updatedAtEpochMillis),
        )
    }
}
