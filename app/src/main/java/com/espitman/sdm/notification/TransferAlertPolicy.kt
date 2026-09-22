package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

internal data class CompletionAlertPlan(
    val toPost: List<Download> = emptyList(),
)

/** Tracks live state transitions; the first snapshot is deliberately seed-only. */
internal class CompletionAlertTracker {
    private val states = mutableMapOf<String, DownloadState>()
    private var seeded = false

    fun observe(downloads: List<Download>, enabled: Boolean): CompletionAlertPlan {
        if (!seeded) {
            states.clear()
            downloads.associateTo(states) { it.id to it.state }
            seeded = true
            return CompletionAlertPlan()
        }

        val present = downloads.mapTo(hashSetOf()) { it.id }
        states.keys.removeAll { it !in present }
        val toPost = buildList {
            downloads.forEach { download ->
                val previous = states[download.id]
                if (
                    enabled &&
                    previous != null &&
                    previous != DownloadState.COMPLETED &&
                    download.state == DownloadState.COMPLETED
                ) {
                    add(download)
                }
                states[download.id] = download.state
            }
        }
        return CompletionAlertPlan(toPost)
    }
}

internal data class StallAlertPlan(
    val toPost: List<Download> = emptyList(),
    val idsToCancel: Set<String> = emptySet(),
)

/** Pure monotonic stall state machine. Any byte-count change starts a new episode. */
internal class StallAlertTracker(
    private val thresholdMs: Long = STALL_THRESHOLD_MS,
) {
    private data class Watch(
        val downloadedBytes: Long,
        val lastProgressElapsedMs: Long,
        val alertPosted: Boolean,
    )

    private val watches = mutableMapOf<String, Watch>()

    init {
        require(thresholdMs > 0L) { "Stall threshold must be positive" }
    }

    fun observe(
        downloads: List<Download>,
        enabled: Boolean,
        nowElapsedMs: Long,
    ): StallAlertPlan {
        if (!enabled) {
            val cancellations = watches.filterValues { it.alertPosted }.keys.toSet()
            watches.clear()
            return StallAlertPlan(idsToCancel = cancellations)
        }

        val eligible = downloads.filter { it.state.isStallEligible() }
        val eligibleIds = eligible.mapTo(hashSetOf()) { it.id }
        val removed = watches
            .filter { (id, watch) -> id !in eligibleIds && watch.alertPosted }
            .keys
            .toMutableSet()
        watches.keys.removeAll { it !in eligibleIds }

        val toPost = mutableListOf<Download>()
        eligible.forEach { download ->
            val previous = watches[download.id]
            if (
                previous == null ||
                previous.downloadedBytes != download.downloadedBytes ||
                nowElapsedMs < previous.lastProgressElapsedMs
            ) {
                if (previous?.alertPosted == true) removed += download.id
                watches[download.id] = Watch(download.downloadedBytes, nowElapsedMs, false)
            } else if (!previous.alertPosted && nowElapsedMs - previous.lastProgressElapsedMs >= thresholdMs) {
                toPost += download
                watches[download.id] = previous.copy(alertPosted = true)
            }
        }
        return StallAlertPlan(toPost = toPost, idsToCancel = removed)
    }

    companion object {
        const val STALL_THRESHOLD_MS = 30_000L
    }
}

private fun DownloadState.isStallEligible(): Boolean =
    this == DownloadState.CONNECTING || this == DownloadState.DOWNLOADING
