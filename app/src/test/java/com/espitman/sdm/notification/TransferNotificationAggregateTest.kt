package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.ui.RecentTransferSpeedTracker
import com.espitman.sdm.ui.decimalSpeedDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationAggregateTest {
    @Test
    fun combinesUnfinishedDownloadsIntoOneProgressAndStatus() {
        val snapshot = TransferNotificationAggregate.from(listOf(
            record("active", DownloadState.DOWNLOADING, 40L, 100L),
            record("queued", DownloadState.QUEUED, 10L, 100L),
            record("paused", DownloadState.PAUSED, 50L, 200L),
            record("done", DownloadState.COMPLETED, 100L, 100L),
        ))

        assertEquals(3, snapshot.pendingCount)
        assertEquals("1 active | 1 queued | 1 paused", snapshot.statusText)
        assertFalse(snapshot.progress.indeterminate)
        assertEquals(25, snapshot.progress.percent)
        assertEquals("100 B / 400 B", snapshot.progress.text)
        assertEquals("0 MB/s", snapshot.speedText)
        assertEquals("0 MB/s | 100 B / 400 B | 1 active | 1 queued | 1 paused", snapshot.compactText())
        assertFalse(snapshot.compactText().contains('\n'))
        assertTrue(snapshot.compactText().startsWith(snapshot.speedText))
    }

    @Test
    fun compactTextIsSingleLineWithSpeedVolumeAndCounts() {
        val snapshot = TransferNotificationAggregate.from(
            downloads = listOf(record("active", DownloadState.DOWNLOADING, 40L, 100L)),
            bytesPerSecond = 1_500_000L,
        )
        assertEquals("2 MB/s", snapshot.speedText)
        assertEquals("2 MB/s | 40 B / 100 B | 1 active", snapshot.compactText())
        assertFalse(snapshot.compactText().contains('\n'))
        assertTrue(snapshot.compactText().startsWith("2 MB/s"))
        assertEquals(
            "2 MB/s | 40 B / 100 B | 1 active | 2 completed | 1 stalled",
            snapshot.compactText(completedSinceStart = 2, stalledCount = 1),
        )
    }

    @Test
    fun unknownSizeMakesAggregateProgressIndeterminate() {
        val snapshot = TransferNotificationAggregate.from(listOf(
            record("active", DownloadState.CONNECTING, 5L, null),
            record("queued", DownloadState.QUEUED, 0L, 100L),
        ))

        assertEquals("1 active | 1 queued", snapshot.statusText)
        assertTrue(snapshot.progress.indeterminate)
        assertEquals("5 B", snapshot.progress.text)
        assertEquals("0 MB/s | 5 B | 1 active | 1 queued", snapshot.compactText())
        assertFalse(snapshot.compactText().contains('\n'))
    }

    @Test
    fun liveSpeedAndStaleDecayUseSharedTrackerAndDecimalDisplay() {
        val tracker = RecentTransferSpeedTracker()
        val first = record("live", DownloadState.DOWNLOADING, 0L, 2_000_000L)
            .copy(updatedAtEpochMillis = 1_000L)
        assertEquals(0L, tracker.aggregateBytesPerSecond(listOf(first), 1_000L))

        val progressing = first.copy(downloadedBytes = 1_000_000L, updatedAtEpochMillis = 2_000L)
        val liveRate = tracker.aggregateBytesPerSecond(listOf(progressing), 2_000L)
        assertEquals(1_000_000L, liveRate)
        val live = TransferNotificationAggregate.from(listOf(progressing), liveRate)
        assertEquals(decimalSpeedDisplay(1_000_000L).formatted, live.speedText)
        assertEquals("1 MB/s | 976.56 KB / 1.91 MB | 1 active", live.compactText())
        assertFalse(live.compactText().contains('\n'))
        assertTrue(live.compactText().startsWith(live.speedText))

        val held = tracker.aggregateBytesPerSecond(
            listOf(progressing),
            2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS,
        )
        assertEquals(1_000_000L, held)
        val stale = tracker.aggregateBytesPerSecond(
            listOf(progressing),
            2_000L + RecentTransferSpeedTracker.DEFAULT_STALE_WINDOW_MILLIS + 1L,
        )
        assertEquals(0L, stale)
        assertEquals("0 MB/s", TransferNotificationAggregate.from(listOf(progressing), stale).speedText)
    }

    @Test
    fun terminalDownloadsDoNotKeepAnOngoingSummary() {
        val snapshot = TransferNotificationAggregate.from(listOf(
            record("done", DownloadState.COMPLETED, 100L, 100L),
            record("failed", DownloadState.FAILED, 3L, 100L),
        ))
        assertEquals(0, snapshot.pendingCount)
        assertEquals("", snapshot.statusText)
    }

    private fun record(id: String, state: DownloadState, downloaded: Long, total: Long?) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        destinationPath = "/downloads/$id.bin",
        totalBytes = total,
        downloadedBytes = downloaded,
        state = state,
        error = if (state == DownloadState.FAILED) "failed" else null,
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = 1L,
        startedAtEpochMillis = null,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 2L else null,
    )
}
