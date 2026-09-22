package com.espitman.sdm.domain

object DownloadAllMutation {
    private val REQUEUE_STATES = setOf(
        DownloadState.PAUSED,
        DownloadState.FAILED,
        DownloadState.CANCELLED,
    )

    fun shouldRequeue(state: DownloadState): Boolean = state in REQUEUE_STATES

    fun apply(current: Download, nowEpochMillis: Long): Download? {
        return when (current.state) {
            DownloadState.PAUSED -> DownloadResumeMutation.apply(current, nowEpochMillis)
            DownloadState.FAILED, DownloadState.CANCELLED -> DownloadStateMachine.transition(
                current,
                DownloadState.QUEUED,
                maxOf(nowEpochMillis, current.updatedAtEpochMillis),
            )
            DownloadState.QUEUED,
            DownloadState.CONNECTING,
            DownloadState.DOWNLOADING,
            DownloadState.COMPLETED -> null
        }
    }
}
