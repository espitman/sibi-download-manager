package com.espitman.sdm.domain

object PauseQueuedMutation {
    fun apply(
        current: Download,
        nowEpochMillis: Long,
        pauseCause: DownloadPauseCause? = null,
    ): Download? {
        if (current.state != DownloadState.QUEUED) return null
        return DownloadStateMachine.transition(
            current,
            DownloadState.PAUSED,
            maxOf(nowEpochMillis, current.updatedAtEpochMillis),
        ).copy(pauseCause = pauseCause)
    }
}
