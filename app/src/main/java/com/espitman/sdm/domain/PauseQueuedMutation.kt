package com.espitman.sdm.domain

object PauseQueuedMutation {
    fun apply(current: Download, nowEpochMillis: Long): Download? {
        if (current.state != DownloadState.QUEUED) return null
        return DownloadStateMachine.transition(
            current,
            DownloadState.PAUSED,
            maxOf(nowEpochMillis, current.updatedAtEpochMillis),
        )
    }
}
