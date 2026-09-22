package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFailure
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCardMappingTest {
    private fun record(
        id: String = "download-id",
        state: DownloadState,
        fileName: String = "$id.zip",
        totalBytes: Long? = 1_000L,
        downloadedBytes: Long = 0L,
        startedAt: Long? = null,
        completedAt: Long? = null,
        error: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = fileName,
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
        assertEquals("63 KB/s", card.metadataValue)
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
        assertEquals("2 KB/s", card.metadataValue)
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
        assertEquals("Download failed", failed.metadataValue)
        assertEquals("Retry", failed.trailing)
        assertTrue(failed.showPlayAction)
    }

    @Test
    fun everyDownloadStateLandsInExactlyOneOpenDesignCategory() {
        val expected = mapOf(
            DownloadState.QUEUED to DownloadCategory.Queued,
            DownloadState.COMPLETED to DownloadCategory.Completed,
            DownloadState.CONNECTING to DownloadCategory.Downloading,
            DownloadState.DOWNLOADING to DownloadCategory.Downloading,
            DownloadState.PAUSED to DownloadCategory.Downloading,
            DownloadState.FAILED to DownloadCategory.Downloading,
            DownloadState.CANCELLED to DownloadCategory.Downloading,
        )
        assertEquals(DownloadState.entries.toSet(), expected.keys)

        DownloadState.entries.forEach { state ->
            val card = mapDownloadToCard(recordFor(state), nowEpochMillis = 2_000L)
            assertEquals(expected.getValue(state), card.category)
            DownloadCategory.entries.forEach { category ->
                val ids = filterDownloadCards(listOf(card), category, "").map { it.id }
                if (category == card.category) {
                    assertEquals(listOf(card.id), ids)
                } else {
                    assertTrue(ids.isEmpty())
                }
            }
        }
    }

    @Test
    fun filenameSearchIsCaseInsensitiveAndCombinedWithCategory() {
        val queuedDune = mapDownloadToCard(
            record(id = "queued-dune", state = DownloadState.QUEUED, fileName = "Dune.Part.Two.mkv"),
            nowEpochMillis = 2_000L,
        )
        val queuedOther = mapDownloadToCard(
            record(id = "queued-other", state = DownloadState.QUEUED, fileName = "Other.mkv"),
            nowEpochMillis = 2_000L,
        )
        val downloadingDune = mapDownloadToCard(
            record(id = "active-dune", state = DownloadState.DOWNLOADING, fileName = "dune.mkv", downloadedBytes = 100L, startedAt = 1_000L),
            nowEpochMillis = 2_000L,
        )
        val cards = listOf(queuedDune, queuedOther, downloadingDune)

        assertEquals(listOf("queued-dune"), filterDownloadCards(cards, DownloadCategory.Queued, "dune").map { it.id })
        assertEquals(listOf("queued-dune"), filterDownloadCards(cards, DownloadCategory.Queued, "DUNE").map { it.id })
        assertEquals(listOf("active-dune"), filterDownloadCards(cards, DownloadCategory.Downloading, "DuNe").map { it.id })
        assertTrue(filterDownloadCards(cards, DownloadCategory.Completed, "dune").isEmpty())
        assertTrue(filterDownloadCards(cards, DownloadCategory.Queued, "no-such-file").isEmpty())
    }

    @Test
    fun filteringReflectsStateTransitionImmediately() {
        var live = record(id = "moving", state = DownloadState.QUEUED)
        fun visible(category: DownloadCategory, query: String = "") =
            filterDownloadCards(listOf(mapDownloadToCard(live, nowEpochMillis = 2_000L)), category, query).map { it.id }

        assertEquals(listOf("moving"), visible(DownloadCategory.Queued))
        assertTrue(visible(DownloadCategory.Downloading).isEmpty())
        assertTrue(visible(DownloadCategory.Completed).isEmpty())

        live = live.copy(state = DownloadState.DOWNLOADING, downloadedBytes = 500L, startedAtEpochMillis = 1_000L)
        assertEquals(listOf("moving"), visible(DownloadCategory.Downloading, "MOV"))
        assertTrue(visible(DownloadCategory.Queued).isEmpty())
        assertTrue(visible(DownloadCategory.Completed).isEmpty())

        live = live.copy(
            state = DownloadState.COMPLETED,
            downloadedBytes = 1_000L,
            completedAtEpochMillis = 3_000L,
        )
        val current = listOf(mapDownloadToCard(live, nowEpochMillis = 3_000L))
        assertEquals(listOf("moving"), filterDownloadCards(current, DownloadCategory.Completed, "mov").map { it.id })
        assertTrue(filterDownloadCards(current, DownloadCategory.Queued, "").isEmpty())
        assertTrue(filterDownloadCards(current, DownloadCategory.Downloading, "").isEmpty())
        assertEquals(
            listOf("moving"),
            DownloadCategory.entries.flatMap { filterDownloadCards(current, it, "") }.map { it.id },
        )
    }

    @Test
    fun failedCardsShowClassifiedLabelAndRetryForEveryCategory() {
        val samples = listOf(
            "java.net.UnknownHostException: Unable to resolve host" to DownloadFailure.NETWORK_LOSS,
            "SocketTimeoutException: timeout" to DownloadFailure.TIMEOUT,
            "HTTP 403: Forbidden" to DownloadFailure.EXPIRED_LINK,
            "java.io.IOException: No space left on device" to DownloadFailure.INSUFFICIENT_STORAGE,
            "HTTP 503: Service Unavailable" to DownloadFailure.TRANSIENT_HTTP,
            "HTTP 404: Not Found" to DownloadFailure.HTTP_ERROR,
            "Interrupted when the app process stopped" to DownloadFailure.OTHER,
        )
        assertEquals(DownloadFailure.entries.toSet(), samples.map { it.second }.toSet())

        samples.forEachIndexed { index, (error, failure) ->
            val card = mapDownloadToCard(
                record(
                    id = "failed-$index",
                    state = DownloadState.FAILED,
                    downloadedBytes = 250L,
                    error = error,
                ),
                nowEpochMillis = 2_000L,
            )
            assertEquals(failure.label, card.metadataValue)
            assertEquals("Retry", card.trailing)
            assertTrue(card.showPlayAction)
            assertEquals(DownloadCategory.Downloading, card.category)
        }
    }

    private fun recordFor(state: DownloadState) = when (state) {
        DownloadState.FAILED -> record(id = state.name.lowercase(), state = state, error = "Network error")
        DownloadState.COMPLETED -> record(
            id = state.name.lowercase(),
            state = state,
            downloadedBytes = 1_000L,
            completedAt = 2_000L,
        )
        else -> record(id = state.name.lowercase(), state = state)
    }

    @Test
    fun clockEtaUsesReferenceStyle() {
        assertEquals("01:04 left", formatClockEta(64L))
        assertEquals("01:15:00 left", formatClockEta(4_500L))
        assertEquals("Calculating…", formatClockEta(null))
    }

    @Test
    fun cardSpeedUsesWholeUnits() {
        assertEquals("—", formatCardSpeed(0L))
        assertEquals("500 B/s", formatCardSpeed(500L))
        assertEquals("6 MB/s", formatCardSpeed((6.2 * 1024 * 1024).toLong()))
        assertEquals("12 MB/s", formatCardSpeed((12.4 * 1024 * 1024).toLong()))
    }

    @Test
    fun clearingCompletedHistoryDoesNotHideOtherCategories() {
        val completed = mapDownloadToCard(recordFor(DownloadState.COMPLETED), 3_000L)
        val paused = mapDownloadToCard(recordFor(DownloadState.PAUSED), 3_000L)
        val queued = mapDownloadToCard(recordFor(DownloadState.QUEUED), 3_000L)

        assertEquals(
            listOf(paused, queued),
            visibleDownloadCards(listOf(completed, paused, queued), setOf(completed.id, paused.id)),
        )
    }
}
