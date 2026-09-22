package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class DownloadStatusCardMetricsTest {
    private val zone = ZoneOffset.ofHours(3)
    private val noon = 1_700_000_000_000L

    private fun record(
        id: String = "download-id",
        state: DownloadState = DownloadState.QUEUED,
        totalBytes: Long? = 1_000L,
        downloadedBytes: Long = 0L,
        createdAt: Long = noon,
        error: String? = null,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
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
    fun emptyRecordsShowZeroForAllFiveValues() {
        val values = downloadStatusCardValues(emptyList(), downloadedTodayBytes = 0L, recentBytesPerSecond = 0L)

        assertEquals(0, values.activeCount)
        assertEquals("0", values.speedValue)
        assertEquals("0 B", values.downloadedToday)
        assertEquals("0 B", values.remaining)
        assertEquals("0", values.connections)
    }

    @Test
    fun mixedConnectingAndDownloadingCountActiveAndLiveStreamsSeparately() {
        val values = downloadStatusCardValues(
            listOf(
                record(id = "connecting", state = DownloadState.CONNECTING, downloadedBytes = 0L),
                record(id = "downloading", state = DownloadState.DOWNLOADING, downloadedBytes = 10L),
                record(id = "queued", state = DownloadState.QUEUED),
                record(id = "paused", state = DownloadState.PAUSED, downloadedBytes = 40L),
            ),
            downloadedTodayBytes = 0L,
            recentBytesPerSecond = 0L,
        )

        assertEquals(2, values.activeCount)
        assertEquals("1", values.connections)
        assertEquals("0.0", values.speedValue)
    }

    @Test
    fun remainingUsesActiveAndConnectingUnknownMarkerClampAndSaturation() {
        val none = downloadStatusCardValues(emptyList(), 0L, 0L)
        assertEquals("0 B", none.remaining)

        val unknown = downloadStatusCardValues(
            listOf(
                record(id = "connecting", state = DownloadState.CONNECTING, totalBytes = 100L, downloadedBytes = 10L),
                record(id = "unknown", state = DownloadState.DOWNLOADING, totalBytes = null, downloadedBytes = 10L),
            ),
            downloadedTodayBytes = 0L,
            recentBytesPerSecond = 1_572_864L,
        )
        assertEquals("—", unknown.remaining)
        assertEquals("1.5", unknown.speedValue)

        val remaining = downloadStatusCardValues(
            listOf(
                record(id = "left", state = DownloadState.DOWNLOADING, totalBytes = 1_000L, downloadedBytes = 250L),
                record(id = "connecting", state = DownloadState.CONNECTING, totalBytes = 400L, downloadedBytes = 400L),
            ),
            downloadedTodayBytes = 0L,
            recentBytesPerSecond = 0L,
        )
        assertEquals(formatBytes(750L), remaining.remaining)

        assertEquals(0L, remainingBytesContribution(10L, 15L))
        assertEquals(0L, remainingBytesContribution(10L, 10L))
        assertEquals(4L, remainingBytesContribution(10L, 6L))

        val overflow = downloadStatusCardValues(
            listOf(
                record(
                    id = "remain-a",
                    state = DownloadState.DOWNLOADING,
                    totalBytes = Long.MAX_VALUE,
                    downloadedBytes = 0L,
                ),
                record(
                    id = "remain-b",
                    state = DownloadState.CONNECTING,
                    totalBytes = 2L,
                    downloadedBytes = 0L,
                ),
            ),
            downloadedTodayBytes = 0L,
            recentBytesPerSecond = 0L,
        )
        assertEquals(formatBytes(Long.MAX_VALUE), overflow.remaining)
        assertEquals(Long.MAX_VALUE, saturatingAdd(Long.MAX_VALUE - 1L, 2L))
    }

    @Test
    fun persistedDailyTotalAndMeasuredSpeedAreFormattedWithoutInference() {
        val idle = downloadStatusCardValues(
            listOf(record(id = "done", state = DownloadState.COMPLETED, totalBytes = 400L, downloadedBytes = 400L, completedAt = noon)),
            downloadedTodayBytes = 8_192L,
            recentBytesPerSecond = 9_437_184L,
        )
        assertEquals(0, idle.activeCount)
        assertEquals("0", idle.speedValue)
        assertEquals(formatBytes(8_192L), idle.downloadedToday)
        assertEquals("0 B", idle.remaining)
        assertEquals("0", idle.connections)

        val live = downloadStatusCardValues(
            listOf(record(id = "live", state = DownloadState.DOWNLOADING, totalBytes = null, downloadedBytes = 50L)),
            downloadedTodayBytes = 250L,
            recentBytesPerSecond = 2_097_152L,
        )
        assertEquals(1, live.activeCount)
        assertEquals("2.0", live.speedValue)
        assertEquals(formatBytes(250L), live.downloadedToday)
        assertEquals("—", live.remaining)
        assertEquals("1", live.connections)
    }

    @Test
    fun idleWakeIsBoundedUntilLocalMidnightAndActiveTicksEverySecond() {
        val dayStart = LocalDate.of(2023, 11, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val nextMidnight = LocalDate.of(2023, 11, 15).atStartOfDay(zone).toInstant().toEpochMilli()
        val justBeforeMidnight = nextMidnight - 1L

        assertEquals(
            ACTIVE_STATUS_REFRESH_DELAY_MILLIS,
            nextDownloadsStatusRefreshDelayMillis(noon, zone, hasActiveTransfers = true),
        )
        assertEquals(
            1L,
            nextDownloadsStatusRefreshDelayMillis(justBeforeMidnight, zone, hasActiveTransfers = false),
        )
        assertEquals(
            60_000L,
            nextDownloadsStatusRefreshDelayMillis(
                dayStart,
                zone,
                hasActiveTransfers = false,
                maxIdleDelayMillis = 60_000L,
            ),
        )
        assertEquals(nextMidnight, startOfNextLocalDay(dayStart, zone))
        assertEquals(
            90_000L,
            nextDownloadsStatusRefreshDelayMillis(
                nextMidnight - 90_000L,
                zone,
                hasActiveTransfers = false,
                maxIdleDelayMillis = 120_000L,
            ),
        )
    }

    @Test
    fun transferredBytesForLocalDayOrZeroRethrowsCancellationAndMapsFailuresToZero() {
        runBlocking {
            assertEquals(12L, transferredBytesForLocalDayOrZero { 12L })
            assertEquals(0L, transferredBytesForLocalDayOrZero { throw IllegalStateException("query failed") })
        }
        val cancelled = assertThrows(CancellationException::class.java) {
            runBlocking {
                transferredBytesForLocalDayOrZero { throw CancellationException("effect cancelled") }
            }
        }
        assertEquals("effect cancelled", cancelled.message)
    }
}
