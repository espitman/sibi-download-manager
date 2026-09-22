package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentTransferSpeedTrackerTest {

    @Test
    fun perDownloadRatesSumToDisplayedAggregate() {
        val tracker = RecentTransferSpeedTracker()
        val first = record("first", downloadedBytes = 0L)
        val second = record("second", downloadedBytes = 0L)
        tracker.bytesPerSecondById(listOf(first, second), 1_000L)

        val rates = tracker.bytesPerSecondById(
            listOf(first.copy(downloadedBytes = 1_000L), second.copy(downloadedBytes = 2_000L)),
            2_000L,
        )

        assertEquals(mapOf("first" to 1_000L, "second" to 2_000L), rates)
        assertEquals(3_000L, rates.values.fold(0L, ::saturatingAdd))
    }

    @Test
    fun rateUsesTransferProgressTimeInsteadOfRapidUiObservationTime() {
        val tracker = RecentTransferSpeedTracker()
        val first = record("live", downloadedBytes = 0L, totalBytes = 2_000_000L)
            .copy(updatedAtEpochMillis = 1_000L)
        tracker.bytesPerSecondById(listOf(first), 10_000L)

        val next = first.copy(downloadedBytes = 1_048_576L, updatedAtEpochMillis = 2_000L)
        assertEquals(
            1_048_576L,
            tracker.bytesPerSecondById(listOf(next), 10_010L)["live"],
        )
    }

    @Test
    fun tooShortMeasurementWaitsForEnoughTransferTime() {
        val tracker = RecentTransferSpeedTracker()
        val first = record("live", downloadedBytes = 0L).copy(updatedAtEpochMillis = 1_000L)
        tracker.bytesPerSecondById(listOf(first), 1_000L)

        val early = first.copy(downloadedBytes = 10_000L, updatedAtEpochMillis = 1_010L)
        assertEquals(emptyMap<String, Long>(), tracker.bytesPerSecondById(listOf(early), 1_010L))

        val later = early.copy(downloadedBytes = 100_000L, updatedAtEpochMillis = 2_000L)
        assertEquals(100_000L, tracker.bytesPerSecondById(listOf(later), 2_000L)["live"])
    }

    private fun record(
        id: String,
        state: DownloadState = DownloadState.DOWNLOADING,
        downloadedBytes: Long = 0L,
        totalBytes: Long? = 1_000_000L,
        createdAt: Long = 1_000L,
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
        startedAtEpochMillis = createdAt,
        completedAtEpochMillis = completedAt,
    )

    @Test
    fun firstObservationAndConnectingContributeZero() {
        val tracker = RecentTransferSpeedTracker()
        val downloading = record("a", downloadedBytes = 4_096L)
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(downloading), 2_000L))
        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(record("b", state = DownloadState.CONNECTING, downloadedBytes = 512L)),
                3_000L,
            ),
        )
    }

    @Test
    fun laterPositiveByteAndTimeDeltaYieldsOverflowSafeRate() {
        val tracker = RecentTransferSpeedTracker()
        val first = record("a", downloadedBytes = 100L)
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(first), 1_000L))
        assertEquals(2_000L, tracker.aggregateBytesPerSecond(listOf(first.copy(downloadedBytes = 1_100L)), 1_500L))
        assertEquals(0L, overflowSafeBytesPerSecond(0L, 1_000L))
        assertEquals(0L, overflowSafeBytesPerSecond(10L, 0L))
        assertEquals(Long.MAX_VALUE, overflowSafeBytesPerSecond(Long.MAX_VALUE, 1L))
    }

    @Test
    fun repeatedRewoundAndNonPositiveTimeResetLiveSample() {
        val tracker = RecentTransferSpeedTracker()
        val live = record("a", downloadedBytes = 0L)
        tracker.aggregateBytesPerSecond(listOf(live), 1_000L)
        tracker.aggregateBytesPerSecond(listOf(live.copy(downloadedBytes = 1_000L)), 2_000L)
        assertEquals(1_000L, tracker.aggregateBytesPerSecond(listOf(live.copy(downloadedBytes = 1_000L)), 2_000L))
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(live.copy(downloadedBytes = 1_000L)), 1_900L))
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(live.copy(downloadedBytes = 200L)), 3_000L))
        assertEquals(
            400L,
            tracker.aggregateBytesPerSecond(listOf(live.copy(downloadedBytes = 600L)), 4_000L),
        )
    }

    @Test
    fun pauseResumeAndTerminalEvictThenRestartWithoutLifetimeAverage() {
        val tracker = RecentTransferSpeedTracker()
        val id = "resume"
        tracker.aggregateBytesPerSecond(listOf(record(id, downloadedBytes = 0L)), 1_000L)
        assertEquals(
            5_000L,
            tracker.aggregateBytesPerSecond(listOf(record(id, downloadedBytes = 5_000L)), 2_000L),
        )
        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(record(id, state = DownloadState.PAUSED, downloadedBytes = 5_000L)),
                2_500L,
            ),
        )
        val resumed = record(id, downloadedBytes = 5_000L)
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(resumed), 3_000L))
        assertEquals(1_000L, tracker.aggregateBytesPerSecond(listOf(resumed.copy(downloadedBytes = 6_000L)), 4_000L))

        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(
                    record(
                        id,
                        state = DownloadState.COMPLETED,
                        downloadedBytes = 1_000_000L,
                        completedAt = 5_000L,
                    ),
                ),
                5_000L,
            ),
        )
        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(record(id, state = DownloadState.FAILED, downloadedBytes = 6_000L, error = "lost")),
                6_000L,
            ),
        )
        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(record(id, state = DownloadState.CANCELLED, downloadedBytes = 6_000L)),
                7_000L,
            ),
        )
    }

    @Test
    fun unknownTotalSizeAndMultipleDownloadsSaturate() {
        val tracker = RecentTransferSpeedTracker()
        val unknown = record("unknown", downloadedBytes = 0L, totalBytes = null)
        val other = record("other", downloadedBytes = 0L)
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(unknown, other), 1_000L))
        assertEquals(
            3_000L,
            tracker.aggregateBytesPerSecond(
                listOf(
                    unknown.copy(downloadedBytes = 1_000L),
                    other.copy(downloadedBytes = 2_000L),
                ),
                2_000L,
            ),
        )

        val overflow = RecentTransferSpeedTracker()
        val hugeA = record("huge-a", downloadedBytes = 0L, totalBytes = Long.MAX_VALUE)
        val hugeB = record("huge-b", downloadedBytes = 0L, totalBytes = Long.MAX_VALUE)
        overflow.aggregateBytesPerSecond(listOf(hugeA, hugeB), 1_000L)
        assertEquals(
            Long.MAX_VALUE,
            overflow.aggregateBytesPerSecond(
                listOf(
                    hugeA.copy(downloadedBytes = Long.MAX_VALUE),
                    hugeB.copy(downloadedBytes = Long.MAX_VALUE),
                ),
                1_250L,
            ),
        )
    }

    @Test
    fun staleWindowHoldsThenDecaysAndDisappearedIdsAreRemoved() {
        val tracker = RecentTransferSpeedTracker()
        val a = record("keep", downloadedBytes = 0L)
        val b = record("drop", downloadedBytes = 0L)
        tracker.aggregateBytesPerSecond(listOf(a, b), 1_000L)
        assertEquals(
            2_000L,
            tracker.aggregateBytesPerSecond(
                listOf(a.copy(downloadedBytes = 1_000L), b.copy(downloadedBytes = 1_000L)),
                2_000L,
            ),
        )
        val heldAtWindowEnd = tracker.aggregateBytesPerSecond(
            listOf(a.copy(downloadedBytes = 1_000L)),
            2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS,
        )
        assertEquals(1_000L, heldAtWindowEnd)
        assertEquals(
            0L,
            tracker.aggregateBytesPerSecond(
                listOf(a.copy(downloadedBytes = 1_000L)),
                2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS + 1L,
            ),
        )
        assertEquals(
            500L,
            tracker.aggregateBytesPerSecond(listOf(a.copy(downloadedBytes = 1_500L)), 4_001L + 1_000L),
        )
    }
}
