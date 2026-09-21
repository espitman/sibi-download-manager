package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class DownloadStatusCardMetricsTest {
    private val zone = ZoneOffset.ofHours(3)
    private val noon = 1_700_000_000_000L
    private val dayStart = startOfLocalDay(noon, zone)
    private val nextDayStart = startOfLocalDayExclusiveEnd(noon, zone)

    private fun record(
        id: String = "download-id",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long? = 1_000L,
        downloadedBytes: Long = 0L,
        createdAt: Long = dayStart,
        updatedAt: Long = createdAt,
        startedAt: Long? = null,
        completedAt: Long? = null,
        error: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = updatedAt,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
    )

    @Test
    fun emptyRecordsShowZeroTrafficAndConnections() {
        val values = downloadStatusCardValues(emptyList(), noon, zone)

        assertEquals(0, values.activeCount)
        assertEquals("0", values.speedValue)
        assertEquals("0 B", values.downloadedToday)
        assertEquals("0 B", values.remaining)
        assertEquals("0", values.connections)
    }

    @Test
    fun connectionsMatchOneStreamPerActiveRecord() {
        val values = downloadStatusCardValues(
            listOf(
                record(id = "connecting", state = DownloadState.CONNECTING, downloadedBytes = 0L, startedAt = noon),
                record(id = "downloading", state = DownloadState.DOWNLOADING, downloadedBytes = 10L, startedAt = noon),
                record(id = "queued", state = DownloadState.QUEUED),
            ),
            noon,
            zone,
        )

        assertEquals(2, values.activeCount)
        assertEquals("2", values.connections)
    }

    @Test
    fun downloadedTodayIncludesActiveRecordsEvenIfStartedYesterday() {
        val yesterday = dayStart - 60_000L
        val active = record(
            id = "overnight",
            state = DownloadState.DOWNLOADING,
            downloadedBytes = 250L,
            createdAt = yesterday,
            updatedAt = yesterday,
            startedAt = yesterday,
        )

        assertTrue(countsTowardDownloadedToday(active, dayStart, nextDayStart))
        assertEquals(250L, sumDownloadedTodayBytes(listOf(active), noon, zone))
    }

    @Test
    fun downloadedTodayIncludesRecordsUpdatedOrCompletedToday() {
        val completedToday = record(
            id = "done-today",
            state = DownloadState.COMPLETED,
            totalBytes = 400L,
            downloadedBytes = 400L,
            createdAt = dayStart,
            updatedAt = noon,
            startedAt = dayStart,
            completedAt = noon,
        )
        val pausedToday = record(
            id = "paused-today",
            state = DownloadState.PAUSED,
            downloadedBytes = 50L,
            createdAt = dayStart,
            updatedAt = noon,
            startedAt = dayStart,
        )
        val completedYesterday = record(
            id = "done-yesterday",
            state = DownloadState.COMPLETED,
            totalBytes = 800L,
            downloadedBytes = 800L,
            createdAt = dayStart - 86_400_000L,
            updatedAt = dayStart - 1L,
            startedAt = dayStart - 86_400_000L,
            completedAt = dayStart - 1L,
        )

        assertEquals(450L, sumDownloadedTodayBytes(listOf(completedToday, pausedToday, completedYesterday), noon, zone))
        assertFalse(countsTowardDownloadedToday(completedYesterday, dayStart, nextDayStart))
    }

    @Test
    fun downloadedTodayUsesLocalDayBoundsAndSaturatesOverflow() {
        val justBeforeMidnight = dayStart - 1L
        val atMidnight = dayStart
        val justBeforeNext = nextDayStart - 1L
        val atNext = nextDayStart
        val yesterdayUpdate = record(
            id = "before-day",
            state = DownloadState.PAUSED,
            downloadedBytes = 90L,
            createdAt = justBeforeMidnight,
            updatedAt = justBeforeMidnight,
            startedAt = justBeforeMidnight,
        )
        val todayStartUpdate = record(
            id = "start-day",
            state = DownloadState.PAUSED,
            downloadedBytes = 10L,
            createdAt = atMidnight,
            updatedAt = atMidnight,
            startedAt = atMidnight,
        )
        val todayEndUpdate = record(
            id = "end-day",
            state = DownloadState.PAUSED,
            downloadedBytes = 20L,
            createdAt = dayStart,
            updatedAt = justBeforeNext,
            startedAt = dayStart,
        )
        val nextDayUpdate = record(
            id = "next-day",
            state = DownloadState.PAUSED,
            downloadedBytes = 40L,
            createdAt = dayStart,
            updatedAt = atNext,
            startedAt = dayStart,
        )

        assertEquals(
            30L,
            sumDownloadedTodayBytes(
                listOf(yesterdayUpdate, todayStartUpdate, todayEndUpdate, nextDayUpdate),
                noon,
                zone,
            ),
        )
        assertEquals(
            Long.MAX_VALUE,
            saturatingAdd(Long.MAX_VALUE - 1L, 2L),
        )
        assertEquals(
            Long.MAX_VALUE,
            sumDownloadedTodayBytes(
                listOf(
                    record(
                        id = "huge-a",
                        state = DownloadState.DOWNLOADING,
                        totalBytes = Long.MAX_VALUE,
                        downloadedBytes = Long.MAX_VALUE - 1L,
                        createdAt = dayStart,
                        updatedAt = noon,
                        startedAt = dayStart,
                    ),
                    record(
                        id = "huge-b",
                        state = DownloadState.DOWNLOADING,
                        totalBytes = Long.MAX_VALUE,
                        downloadedBytes = 2L,
                        createdAt = dayStart,
                        updatedAt = noon,
                        startedAt = dayStart,
                    ),
                ),
                noon,
                zone,
            ),
        )
    }

    @Test
    fun remainingShowsDashForUnknownSizeAndSaturatesKnownRemainders() {
        val unknown = downloadStatusCardValues(
            listOf(
                record(
                    id = "unknown",
                    state = DownloadState.DOWNLOADING,
                    totalBytes = null,
                    downloadedBytes = 10L,
                    startedAt = noon,
                ),
            ),
            noon,
            zone,
        )
        assertEquals("—", unknown.remaining)

        val remaining = downloadStatusCardValues(
            listOf(
                record(
                    id = "left",
                    state = DownloadState.DOWNLOADING,
                    totalBytes = 1_000L,
                    downloadedBytes = 250L,
                    startedAt = noon,
                ),
            ),
            noon,
            zone,
        )
        assertEquals(formatBytes(750L), remaining.remaining)

        val overflow = remainingBytesOverflowFixture()
        assertEquals(formatBytes(Long.MAX_VALUE), overflow.remaining)
    }

    private fun remainingBytesOverflowFixture(): DownloadStatusCardValues {
        val first = record(
            id = "remain-a",
            state = DownloadState.DOWNLOADING,
            totalBytes = Long.MAX_VALUE,
            downloadedBytes = 0L,
            startedAt = noon,
        )
        val second = record(
            id = "remain-b",
            state = DownloadState.DOWNLOADING,
            totalBytes = 2L,
            downloadedBytes = 0L,
            startedAt = noon,
        )
        return downloadStatusCardValues(listOf(first, second), noon, zone)
    }
}
