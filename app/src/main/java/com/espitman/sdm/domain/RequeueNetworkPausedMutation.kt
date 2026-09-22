package com.espitman.sdm.domain

object RequeueNetworkPausedMutation {
    fun apply(current: Download, nowEpochMillis: Long): Download? {
        if (current.state != DownloadState.PAUSED) return null
        if (current.pauseCause != DownloadPauseCause.NETWORK_POLICY) return null
        return DownloadResumeMutation.apply(current, nowEpochMillis)
    }
}
