package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDetailsSpeedTrackerTest {

    private fun record(
        id: String = "selected",
        state: DownloadState = DownloadState.DOWNLOADING,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 10_000_000L,
        createdAt: Long = 1_000L,
        startedAt: Long? = 1_000L,
        completedAt: Long? = null,
        error: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
    )

    @Test
    fun firstObservationIsZeroAndDoesNotUseLifetimeAverage() {
        val tracker = DownloadDetailsSpeedTracker()
        val alreadyMoved = record(downloadedBytes = 5_000_000L, startedAt = 0L)

        val first = tracker.observe(alreadyMoved, nowEpochMillis = 10_000L)

        assertEquals(0L, first.currentBytesPerSecond)
        assertEquals(listOf(0L), first.samples)
    }

    @Test
    fun connectingQueuedPausedAndTerminalResetToZeroWithoutSamples() {
        val tracker = DownloadDetailsSpeedTracker()
        tracker.observe(record(downloadedBytes = 0L), 1_000L)
        tracker.observe(record(downloadedBytes = 1_000L), 2_000L)

        val states = listOf(
            record(state = DownloadState.CONNECTING, downloadedBytes = 1_000L),
            record(state = DownloadState.QUEUED, downloadedBytes = 1_000L, startedAt = null),
            record(state = DownloadState.PAUSED, downloadedBytes = 1_000L),
            record(
                state = DownloadState.COMPLETED,
                downloadedBytes = 10_000_000L,
                totalBytes = 10_000_000L,
                completedAt = 3_000L,
            ),
            record(state = DownloadState.FAILED, downloadedBytes = 1_000L, error = "lost"),
            record(state = DownloadState.CANCELLED, downloadedBytes = 1_000L),
        )
        for (snapshot in states) {
            val observed = tracker.observe(snapshot, 4_000L)
            assertEquals(snapshot.state.name, 0L, observed.currentBytesPerSecond)
            assertEquals(snapshot.state.name, emptyList<Long>(), observed.samples)
        }
    }

    @Test
    fun pauseThenResumeStartsANewZeroSampleInsteadOfLifetimeAverage() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 0L)
        tracker.observe(live, 1_000L)
        assertEquals(2_000L, tracker.observe(live.copy(downloadedBytes = 2_000L), 2_000L).currentBytesPerSecond)

        val paused = tracker.observe(live.copy(state = DownloadState.PAUSED, downloadedBytes = 2_000L), 3_000L)
        assertEquals(0L, paused.currentBytesPerSecond)
        assertEquals(emptyList<Long>(), paused.samples)

        val resumed = live.copy(downloadedBytes = 2_000L)
        val firstResume = tracker.observe(resumed, 4_000L)
        assertEquals(0L, firstResume.currentBytesPerSecond)
        assertEquals(listOf(0L), firstResume.samples)
        assertEquals(
            500L,
            tracker.observe(resumed.copy(downloadedBytes = 2_500L), 5_000L).currentBytesPerSecond,
        )
    }

    @Test
    fun switchingSelectedIdResetsHistory() {
        val tracker = DownloadDetailsSpeedTracker()
        val first = record(id = "a", downloadedBytes = 0L)
        tracker.observe(first, 1_000L)
        tracker.observe(first.copy(downloadedBytes = 3_000L), 2_000L)

        val other = tracker.observe(record(id = "b", downloadedBytes = 9_000L), 3_000L)
        assertEquals(0L, other.currentBytesPerSecond)
        assertEquals(listOf(0L), other.samples)
        assertEquals(
            1_000L,
            tracker.observe(record(id = "b", downloadedBytes = 10_000L), 4_000L).currentBytesPerSecond,
        )
    }

    @Test
    fun rewindAndNegativeTimeYieldZeroThenMeasureFromTheNewBaseline() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 0L)
        tracker.observe(live, 1_000L)
        tracker.observe(live.copy(downloadedBytes = 4_000L), 2_000L)

        val rewound = tracker.observe(live.copy(downloadedBytes = 1_000L), 3_000L)
        assertEquals(0L, rewound.currentBytesPerSecond)
        assertEquals(listOf(0L), rewound.samples)
        assertEquals(
            2_000L,
            tracker.observe(live.copy(downloadedBytes = 3_000L), 4_000L).currentBytesPerSecond,
        )

        tracker.observe(live.copy(downloadedBytes = 3_000L), 5_000L)
        val backwardClock = tracker.observe(live.copy(downloadedBytes = 4_000L), 4_500L)
        assertEquals(0L, backwardClock.currentBytesPerSecond)
        assertEquals(listOf(0L), backwardClock.samples)
    }

    @Test
    fun zeroElapsedTimeDoesNotAppendEvenWhenBytesIncrease() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 100L)
        tracker.observe(live, 1_000L)
        val moving = tracker.observe(live.copy(downloadedBytes = 1_100L), 2_000L)
        assertEquals(1_000L, moving.currentBytesPerSecond)
        assertEquals(listOf(0L, 1_000L), moving.samples)

        val sameInstant = tracker.observe(live.copy(downloadedBytes = 2_100L), 2_000L)
        assertEquals(0L, sameInstant.currentBytesPerSecond)
        assertEquals(listOf(0L, 1_000L), sameInstant.samples)
    }

    @Test
    fun sixtyIdleTicksAppendZeroAndEvictAPriorNonzeroSample() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 100L)
        tracker.observe(live, 1_000L)
        val moving = tracker.observe(live.copy(downloadedBytes = 1_100L), 2_000L)
        assertEquals(listOf(0L, 1_000L), moving.samples)

        var now = 2_000L
        var snapshot = moving
        repeat(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES) {
            now += 1_000L
            snapshot = tracker.observe(live.copy(downloadedBytes = 1_100L), now)
            assertEquals(0L, snapshot.currentBytesPerSecond)
            assertEquals(0L, snapshot.samples.last())
            assertTrue(snapshot.samples.size <= DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES)
        }

        assertEquals(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES, snapshot.samples.size)
        assertEquals(false, 1_000L in snapshot.samples)
        assertEquals(
            List(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES) { 0L },
            snapshot.samples,
        )
    }

    @Test
    fun oneSecondCadenceKeepsOnlyTheMostRecentSixtySamples() {
        val tracker = DownloadDetailsSpeedTracker()
        var bytes = 0L
        val first = tracker.observe(record(downloadedBytes = bytes), 0L)
        assertEquals(listOf(0L), first.samples)

        val rates = ArrayList<Long>(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES)
        for (second in 1..DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES) {
            bytes += second * 100L
            val snapshot = tracker.observe(record(downloadedBytes = bytes), second * 1_000L)
            rates.add(second * 100L)
            assertEquals(second * 100L, snapshot.currentBytesPerSecond)
            val expectedSize = (second + 1).coerceAtMost(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES)
            assertEquals(expectedSize, snapshot.samples.size)
        }

        val full = tracker.observe(record(downloadedBytes = bytes), 60_000L)
        assertEquals(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES, full.samples.size)
        assertEquals(rates.takeLast(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES), full.samples)
        assertEquals(false, 0L in full.samples)

        bytes += 9_000L
        val overflow = tracker.observe(record(downloadedBytes = bytes), 61_000L)
        assertEquals(DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES, overflow.samples.size)
        assertEquals(9_000L, overflow.currentBytesPerSecond)
        assertEquals(rates.drop(1) + 9_000L, overflow.samples)
        assertEquals(rates[1], overflow.samples.first())
    }

    @Test
    fun explicitResetClearsCapacityAndTimingBaseline() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 0L)
        tracker.observe(live, 1_000L)
        tracker.observe(live.copy(downloadedBytes = 8_000L), 2_000L)
        tracker.reset()

        val afterReset = tracker.observe(live.copy(downloadedBytes = 8_000L), 3_000L)
        assertEquals(0L, afterReset.currentBytesPerSecond)
        assertEquals(listOf(0L), afterReset.samples)
        assertEquals(
            4_000L,
            tracker.observe(live.copy(downloadedBytes = 12_000L), 4_000L).currentBytesPerSecond,
        )
    }

    @Test
    fun subSecondPositiveElapsedStillRecordsInstantaneousRateWithoutAssumingOneSecond() {
        val tracker = DownloadDetailsSpeedTracker()
        val live = record(downloadedBytes = 0L)
        tracker.observe(live, 1_000L)
        val halfSecond = tracker.observe(live.copy(downloadedBytes = 500L), 1_500L)
        assertEquals(1_000L, halfSecond.currentBytesPerSecond)
        assertEquals(listOf(0L, 1_000L), halfSecond.samples)
    }

    @Test
    fun unknownTotalStillSamplesPositiveDeltas() {
        val tracker = DownloadDetailsSpeedTracker()
        val unknown = record(downloadedBytes = 0L, totalBytes = null)
        tracker.observe(unknown, 1_000L)
        val moving = tracker.observe(unknown.copy(downloadedBytes = 2_048L), 2_000L)
        assertEquals(2_048L, moving.currentBytesPerSecond)
        assertEquals(listOf(0L, 2_048L), moving.samples)
    }

    @Test
    fun overflowSafeRateIsStoredAndReturned() {
        val tracker = DownloadDetailsSpeedTracker()
        val huge = record(downloadedBytes = 0L, totalBytes = Long.MAX_VALUE)
        tracker.observe(huge, 1_000L)
        val overflow = tracker.observe(huge.copy(downloadedBytes = Long.MAX_VALUE), 1_001L)
        assertEquals(Long.MAX_VALUE, overflow.currentBytesPerSecond)
        assertEquals(listOf(0L, Long.MAX_VALUE), overflow.samples)
        assertTrue(overflow.samples.size <= DownloadDetailsSpeedTracker.DEFAULT_MAX_SAMPLES)
    }
}
