package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

data class TransferNotificationTeardownPlan(
    val childrenToPost: List<Download>,
    val idsToCancel: Set<String>,
)

object TransferNotificationChildPolicy {
    fun shouldPostChild(state: DownloadState): Boolean = when (state) {
        DownloadState.CONNECTING, DownloadState.DOWNLOADING, DownloadState.PAUSED -> true
        DownloadState.QUEUED,
        DownloadState.COMPLETED,
        DownloadState.FAILED,
        DownloadState.CANCELLED,
        -> false
    }

    fun childrenToPost(downloads: List<Download>): List<Download> =
        downloads.filter { shouldPostChild(it.state) }

    fun activeCount(states: Iterable<DownloadState>): Int = states.count {
        it == DownloadState.CONNECTING || it == DownloadState.DOWNLOADING
    }

    fun idsToCancel(
        postedTags: Set<String>,
        systemChildTags: Set<String>,
        statesById: Map<String, DownloadState>,
    ): Set<String> {
        val retain = statesById.filterValues(::shouldPostChild).keys
        return (postedTags + systemChildTags + statesById.keys) - retain
    }

    fun teardownPlan(
        postedTags: Set<String>,
        systemChildTags: Set<String>,
        downloads: List<Download>,
    ): TransferNotificationTeardownPlan {
        val statesById = downloads.associate { it.id to it.state }
        val childrenToPost = downloads.filter { it.state == DownloadState.PAUSED }
        val retain = childrenToPost.map { it.id }.toSet()
        val idsToCancel = (postedTags + systemChildTags + statesById.keys) - retain
        return TransferNotificationTeardownPlan(
            childrenToPost = childrenToPost,
            idsToCancel = idsToCancel,
        )
    }
}
