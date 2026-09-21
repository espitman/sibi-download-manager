package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCardMappingTest {
    private fun record(
        id: String = "download-id",
        state: DownloadState,
        totalBytes: Long? = 1_000L,
        downloadedBytes: Long = 0L,
        startedAt: Long? = null,
        completedAt: Long? = null,
        error: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        destinationPath = "/downloads/$id.zip",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
    )

    @Test
    fun queuedCardMatchesReferenceCopy() {
        val card = mapDownloadToCard(record(state = DownloadState.QUEUED), nowEpochMillis = 2_000L)

        assertEquals(DownloadCategory.Queued, card.category)
        assertEquals("Queued", card.metadataValue)
        assertEquals("Next in queue", card.progressLabel)
        assertEquals("Wi-Fi only", card.trailing)
        assertEquals(0f, card.progress)
        assertTrue(card.showPlayAction)
    }

    @Test
    fun activeKnownSizeShowsSpeedPercentBytesAndClockEta() {
        val card = mapDownloadToCard(
            record(
                state = DownloadState.DOWNLOADING,
                totalBytes = 128_000L,
                downloadedBytes = 64_000L,
                startedAt = 1_000L,
            ),
            nowEpochMillis = 2_000L,
        )

        assertEquals(DownloadCategory.Downloading, card.category)
        assertEquals("62.5 KB/s", card.metadataValue)
        assertEquals("50% · 62.50 KB", card.progressLabel)
        assertEquals("00:01 left", card.trailing)
        assertEquals(.5f, card.progress)
        assertFalse(card.showPlayAction)
    }

    @Test
    fun activeUnknownSizeShowsSpeedAndCalculatingEta() {
        val card = mapDownloadToCard(
            record(
                state = DownloadState.DOWNLOADING,
                totalBytes = null,
                downloadedBytes = 2_048L,
                startedAt = 1_000L,
            ),
            nowEpochMillis = 2_000L,
        )

        assertEquals("Unknown size", card.size)
        assertEquals("2.0 KB/s", card.metadataValue)
        assertEquals("— · 2.00 KB", card.progressLabel)
        assertEquals("Calculating…", card.trailing)
        assertEquals(0f, card.progress)
    }

    @Test
    fun connectingCardUsesReferenceCalculatingCopy() {
        val card = mapDownloadToCard(
            record(state = DownloadState.CONNECTING),
            nowEpochMillis = 2_000L,
        )

        assertEquals(DownloadCategory.Downloading, card.category)
        assertEquals("Connecting…", card.metadataValue)
        assertEquals("Calculating…", card.trailing)
    }

    @Test
    fun completedCardMovesCategoryAndShowsFullProgress() {
        val card = mapDownloadToCard(
            record(
                state = DownloadState.COMPLETED,
                downloadedBytes = 1_000L,
                startedAt = 1_000L,
                completedAt = 2_000L,
            ),
            nowEpochMillis = 3_000L,
        )

        assertEquals(DownloadCategory.Completed, card.category)
        assertEquals(1f, card.progress)
        assertEquals("100% · 1000 B", card.progressLabel)
        assertEquals("Completed", card.metadataValue)
        assertEquals("Completed", card.trailing)
    }

    @Test
    fun pausedAndFailedRemainInDownloadingWithTheirStateCopy() {
        val paused = mapDownloadToCard(
            record(state = DownloadState.PAUSED, downloadedBytes = 250L),
            nowEpochMillis = 2_000L,
        )
        val failed = mapDownloadToCard(
            record(state = DownloadState.FAILED, downloadedBytes = 250L, error = "Network error"),
            nowEpochMillis = 2_000L,
        )

        assertEquals(DownloadCategory.Downloading, paused.category)
        assertEquals("Paused", paused.metadataValue)
        assertEquals("Paused", paused.trailing)
        assertTrue(paused.showPlayAction)
        assertEquals(DownloadCategory.Downloading, failed.category)
        assertEquals("Failed", failed.metadataValue)
        assertEquals("Failed", failed.trailing)
        assertTrue(failed.showPlayAction)
    }

    @Test
    fun filteringReflectsStateTransitionImmediately() {
        val downloadingRecord = record(
            id = "moving",
            state = DownloadState.DOWNLOADING,
            downloadedBytes = 500L,
            startedAt = 1_000L,
        )
        val activeCard = mapDownloadToCard(downloadingRecord, nowEpochMillis = 2_000L)
        assertEquals(listOf("moving"), filterDownloadCards(listOf(activeCard), DownloadCategory.Downloading, "").map { it.id })
        assertTrue(filterDownloadCards(listOf(activeCard), DownloadCategory.Completed, "").isEmpty())

        val completedCard = mapDownloadToCard(
            downloadingRecord.copy(
                state = DownloadState.COMPLETED,
                downloadedBytes = 1_000L,
                completedAtEpochMillis = 3_000L,
            ),
            nowEpochMillis = 3_000L,
        )
        assertTrue(filterDownloadCards(listOf(completedCard), DownloadCategory.Downloading, "").isEmpty())
        assertEquals(listOf("moving"), filterDownloadCards(listOf(completedCard), DownloadCategory.Completed, "MOV").map { it.id })
    }

    @Test
    fun clockEtaUsesReferenceStyle() {
        assertEquals("01:04 left", formatClockEta(64L))
        assertEquals("01:15:00 left", formatClockEta(4_500L))
        assertEquals("Calculating…", formatClockEta(null))
    }

    @Test
    fun cardSpeedUsesReferenceOneDecimalStyle() {
        assertEquals("—", formatCardSpeed(0L))
        assertEquals("500 B/s", formatCardSpeed(500L))
        assertEquals("6.2 MB/s", formatCardSpeed((6.2 * 1024 * 1024).toLong()))
        assertEquals("12.4 MB/s", formatCardSpeed((12.4 * 1024 * 1024).toLong()))
    }
}
