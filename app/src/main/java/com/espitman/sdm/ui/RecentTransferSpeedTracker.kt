package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

/**
 * Instantaneous-ish aggregate B/s from successive repository snapshots.
 *
 * The last measured rate is held for [staleWindowMillis] (default 2s, twice the engine's
 * 1s progress publication cadence) after bytes stop, then decays to 0.
 */
internal class RecentTransferSpeedTracker(
    private val staleWindowMillis: Long = DEFAULT_STALE_WINDOW_MILLIS,
) {
    init {
        require(staleWindowMillis >= 0L) { "Stale window cannot be negative" }
    }

    private data class LiveSample(
        val downloadedBytes: Long,
        val observedAtEpochMillis: Long,
        val progressAtEpochMillis: Long,
        val bytesPerSecond: Long,
        val rateAtEpochMillis: Long,
    )

    private val live = HashMap<String, LiveSample>()

    fun bytesPerSecondById(snapshots: List<Download>, nowEpochMillis: Long): Map<String, Long> {
        val present = snapshots.mapTo(HashSet()) { it.id }
        live.keys.removeAll { it !in present }

        val rates = HashMap<String, Long>()
        for (snapshot in snapshots) {
            val rate = bytesPerSecondFor(snapshot, nowEpochMillis)
            if (rate > 0L) rates[snapshot.id] = rate
        }
        return rates
    }

    fun aggregateBytesPerSecond(snapshots: List<Download>, nowEpochMillis: Long): Long =
        bytesPerSecondById(snapshots, nowEpochMillis).values.fold(0L, ::saturatingAdd)

    private fun bytesPerSecondFor(snapshot: Download, nowEpochMillis: Long): Long {
        if (snapshot.state != DownloadState.DOWNLOADING) {
            live.remove(snapshot.id)
            return 0L
        }
        val previous = live[snapshot.id]
        if (previous == null) {
            live[snapshot.id] = LiveSample(
                snapshot.downloadedBytes,
                nowEpochMillis,
                snapshot.updatedAtEpochMillis,
                0L,
                nowEpochMillis,
            )
            return 0L
        }
        val byteDelta = snapshot.downloadedBytes - previous.downloadedBytes
        val observationDelta = nowEpochMillis - previous.observedAtEpochMillis
        val progressDelta = snapshot.updatedAtEpochMillis - previous.progressAtEpochMillis
        if (byteDelta < 0L || observationDelta < 0L || progressDelta < 0L) {
            live[snapshot.id] = LiveSample(
                snapshot.downloadedBytes,
                nowEpochMillis,
                snapshot.updatedAtEpochMillis,
                0L,
                nowEpochMillis,
            )
            return 0L
        }
        if (byteDelta > 0L) {
            val timeDelta = if (progressDelta > 0L) progressDelta else observationDelta
            if (timeDelta < MIN_RATE_INTERVAL_MILLIS) return heldRate(previous, nowEpochMillis)
            val rate = overflowSafeBytesPerSecond(byteDelta, timeDelta)
            live[snapshot.id] = LiveSample(
                snapshot.downloadedBytes,
                nowEpochMillis,
                snapshot.updatedAtEpochMillis,
                rate,
                nowEpochMillis,
            )
            return rate
        }
        val held = heldRate(previous, nowEpochMillis)
        if (observationDelta > 0L && held == 0L) {
            live[snapshot.id] = previous.copy(observedAtEpochMillis = nowEpochMillis, bytesPerSecond = 0L)
        }
        return held
    }

    private fun heldRate(previous: LiveSample, nowEpochMillis: Long): Long {
        if (previous.bytesPerSecond <= 0L) return 0L
        if (nowEpochMillis - previous.rateAtEpochMillis > staleWindowMillis) return 0L
        return previous.bytesPerSecond
    }

    companion object {
        const val DEFAULT_STALE_WINDOW_MILLIS = 2_000L
        private const val MIN_RATE_INTERVAL_MILLIS = 250L
    }
}

internal fun overflowSafeBytesPerSecond(byteDelta: Long, timeDeltaMillis: Long): Long {
    if (byteDelta <= 0L || timeDeltaMillis <= 0L) return 0L
    val quotient = byteDelta / timeDeltaMillis
    val remainder = byteDelta % timeDeltaMillis
    val high = saturatingMulNonNegative(quotient, 1_000L)
    val low = if (remainder <= Long.MAX_VALUE / 1_000L) {
        (remainder * 1_000L) / timeDeltaMillis
    } else {
        0L
    }
    return saturatingAdd(high, low)
}

private fun saturatingMulNonNegative(left: Long, right: Long): Long {
    if (left == 0L || right == 0L) return 0L
    return if (left > Long.MAX_VALUE / right) Long.MAX_VALUE else left * right
}
