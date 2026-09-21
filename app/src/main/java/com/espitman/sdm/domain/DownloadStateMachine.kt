package com.espitman.sdm.domain

object DownloadStateMachine {
    private val allowedTransitions = mapOf(
        DownloadState.QUEUED to setOf(
            DownloadState.CONNECTING,
            DownloadState.PAUSED,
            DownloadState.CANCELLED,
        ),
        DownloadState.CONNECTING to setOf(
            DownloadState.DOWNLOADING,
            DownloadState.PAUSED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ),
        DownloadState.DOWNLOADING to setOf(
            DownloadState.PAUSED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        ),
        DownloadState.PAUSED to setOf(
            DownloadState.QUEUED,
            DownloadState.CANCELLED,
        ),
        DownloadState.FAILED to setOf(
            DownloadState.QUEUED,
            DownloadState.CANCELLED,
        ),
        DownloadState.COMPLETED to emptySet(),
        DownloadState.CANCELLED to setOf(DownloadState.QUEUED),
    )

    fun canTransition(from: DownloadState, to: DownloadState): Boolean =
        to in allowedTransitions.getValue(from)

    fun transition(
        download: Download,
        to: DownloadState,
        nowEpochMillis: Long,
        error: String? = null,
    ): Download {
        require(canTransition(download.state, to)) {
            "Invalid download transition: ${download.state} -> $to"
        }
        require(nowEpochMillis >= download.updatedAtEpochMillis) {
            "Transition time cannot move backwards"
        }
        require(to != DownloadState.FAILED || !error.isNullOrBlank()) {
            "A failed transition requires an error"
        }
        require(to == DownloadState.FAILED || error == null) {
            "An error may only accompany a failed transition"
        }

        return download.copy(
            state = to,
            error = error,
            updatedAtEpochMillis = nowEpochMillis,
            startedAtEpochMillis = when {
                download.startedAtEpochMillis != null -> download.startedAtEpochMillis
                to == DownloadState.CONNECTING || to == DownloadState.DOWNLOADING -> nowEpochMillis
                else -> null
            },
            completedAtEpochMillis = if (to == DownloadState.COMPLETED) nowEpochMillis else null,
        )
    }
}
