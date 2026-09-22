package com.espitman.sdm.domain

import kotlin.math.max

object DownloadPriorityMutation {
    const val NORMAL = 0
    const val HIGH = 1

    private val MUTABLE_STATES = setOf(
        DownloadState.QUEUED,
        DownloadState.CONNECTING,
        DownloadState.DOWNLOADING,
        DownloadState.PAUSED,
        DownloadState.FAILED,
    )

    fun isHigh(priority: Int): Boolean = priority > NORMAL

    fun apply(current: Download, priority: Int, nowEpochMillis: Long): Download {
        require(priority >= 0) { "Priority cannot be negative" }
        if (current.state !in MUTABLE_STATES) return current
        if (current.priority == priority) return current
        val timestamp = max(nowEpochMillis, current.updatedAtEpochMillis)
        return current.copy(priority = priority, updatedAtEpochMillis = timestamp)
    }

    fun toggle(current: Download, nowEpochMillis: Long): Download {
        val next = if (isHigh(current.priority)) NORMAL else HIGH
        return apply(current, next, nowEpochMillis)
    }
}
