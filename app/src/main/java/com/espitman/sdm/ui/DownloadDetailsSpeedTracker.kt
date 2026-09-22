package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState

internal data class DownloadDetailsSpeedSnapshot(
    val currentBytesPerSecond: Long,
    val samples: List<Long>,
)

/**
 * Recent per-interval B/s for one selected download.
 *
 * Every positive elapsed-time observation while [DownloadState.DOWNLOADING] appends a
 * sample, including 0 B/s when no bytes arrived, so older rates age out within
 * [DEFAULT_MAX_SAMPLES] ticks. Zero elapsed time is not a measurement interval and
 * does not append. The first observation, rewinds, and selection or state changes
 * yield zero and never a lifetime average.
 */
internal class DownloadDetailsSpeedTracker(
    private val maxSamples: Int = DEFAULT_MAX_SAMPLES,
) {
    init {
        require(maxSamples > 0) { "Sample capacity must be positive" }
    }

    private val samples = ArrayDeque<Long>(maxSamples)
    private var trackedId: String? = null
    private var lastDownloadedBytes: Long? = null
    private var lastObservedAtEpochMillis: Long? = null

    fun reset() {
        samples.clear()
        trackedId = null
        lastDownloadedBytes = null
        lastObservedAtEpochMillis = null
    }

    fun observe(download: Download, nowEpochMillis: Long): DownloadDetailsSpeedSnapshot {
        if (download.state != DownloadState.DOWNLOADING) {
            trackedId = download.id
            lastDownloadedBytes = null
            lastObservedAtEpochMillis = null
            samples.clear()
            return DownloadDetailsSpeedSnapshot(currentBytesPerSecond = 0L, samples = emptyList())
        }
        if (trackedId != download.id || lastDownloadedBytes == null || lastObservedAtEpochMillis == null) {
            return beginSession(download, nowEpochMillis)
        }
        val byteDelta = download.downloadedBytes - lastDownloadedBytes!!
        val timeDelta = nowEpochMillis - lastObservedAtEpochMillis!!
        if (byteDelta < 0L || timeDelta < 0L) {
            return beginSession(download, nowEpochMillis)
        }
        lastDownloadedBytes = download.downloadedBytes
        lastObservedAtEpochMillis = nowEpochMillis
        if (timeDelta == 0L) {
            return snapshot(currentBytesPerSecond = 0L)
        }
        val rate = overflowSafeBytesPerSecond(byteDelta, timeDelta)
        appendSample(rate)
        return snapshot(currentBytesPerSecond = rate)
    }

    private fun beginSession(download: Download, nowEpochMillis: Long): DownloadDetailsSpeedSnapshot {
        trackedId = download.id
        lastDownloadedBytes = download.downloadedBytes
        lastObservedAtEpochMillis = nowEpochMillis
        samples.clear()
        appendSample(0L)
        return snapshot(currentBytesPerSecond = 0L)
    }

    private fun appendSample(bytesPerSecond: Long) {
        if (samples.size == maxSamples) samples.removeFirst()
        samples.addLast(bytesPerSecond)
    }

    private fun snapshot(currentBytesPerSecond: Long) = DownloadDetailsSpeedSnapshot(
        currentBytesPerSecond = currentBytesPerSecond,
        samples = samples.toList(),
    )

    companion object {
        const val DEFAULT_MAX_SAMPLES: Int = 60
    }
}
