package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadFailure
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadCardMappingTest {
    @Test fun pendingRetryShowsCountdownAndPauseThenRevertsToManualRetryWhenDisabled() {
        val d=Download(url="https://example.com/f",fileName="f",createdAtEpochMillis=1000,state=DownloadState.FAILED,error="HTTP 503",failedAtEpochMillis=1000)
        val card=mapDownloadToCard(d,1500,retrySettings=com.espitman.sdm.domain.AutomaticRetrySettings(3,10))
        assertEquals("Retry 1/3",card.metadataValue);assertEquals("Retry in 10s",card.trailing);assertFalse(card.showPlayAction)
        assertEquals("Waiting to retry",mapDownloadToCard(d,12000,retrySettings=com.espitman.sdm.domain.AutomaticRetrySettings(3,10)).trailing)
        assertTrue(mapDownloadToCard(d,1500,retrySettings=com.espitman.sdm.domain.AutomaticRetrySettings(0,10)).showPlayAction)
    }
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
        assertTrue(card.isQueued)
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
            recentBytesPerSecond = 64_000L,
        )

        assertEquals(DownloadCategory.Queued, card.category)
        assertEquals("64 KB/s", card.metadataValue)
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
            recentBytesPerSecond = 2_048L,
        )

        assertEquals("Unknown size", card.size)
        assertEquals("2 KB/s", card.metadataValue)
        assertEquals("— · 2.00 KB", card.progressLabel)
        assertEquals("Calculating…", card.trailing)
        assertEquals(0f, card.progress)
    }

    @Test
    fun activeCardUsesRecentRateForSpeedAndEtaInsteadOfLifetimeAverage() {
        val card = mapDownloadToCard(
            record(
                state = DownloadState.DOWNLOADING,
                totalBytes = 10L * 1024 * 1024,
                downloadedBytes = 5L * 1024 * 1024,
                startedAt = 1_000L,
            ),
            nowEpochMillis = 101_000L,
            recentBytesPerSecond = 2L * 1024 * 1024,
        )

        assertEquals("2 MB/s", card.metadataValue)
        assertEquals("00:03 left", card.trailing)
    }

    @Test
    fun connectingCardUsesReferenceCalculatingCopy() {
        val card = mapDownloadToCard(
            record(state = DownloadState.CONNECTING),
            nowEpochMillis = 2_000L,
        )

        assertEquals(DownloadCategory.Queued, card.category)
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
    fun pausedAndFailedBothAppearInQueueWithTheirOwnStates() {
        val paused = mapDownloadToCard(
            record(state = DownloadState.PAUSED, downloadedBytes = 250L),
            nowEpochMillis = 2_000L,
        )
        val failed = mapDownloadToCard(
            record(state = DownloadState.FAILED, downloadedBytes = 250L, error = "Network error"),
            nowEpochMillis = 2_000L,
        )

        assertEquals(DownloadCategory.Queued, paused.category)
        assertFalse(paused.isQueued)
        assertEquals("1000 B", paused.size)
        assertEquals(.25f, paused.progress)
        assertEquals("25% · 250 B", paused.progressLabel)
        assertEquals("Paused", paused.metadataValue)
        assertEquals("Paused", paused.trailing)
        assertTrue(paused.showPlayAction)
        assertEquals(listOf(paused.id), filterDownloadCards(listOf(paused), DownloadCategory.Queued, "").map { it.id })
        assertEquals(DownloadCategory.Queued, failed.category)
        assertFalse(failed.isQueued)
        assertEquals("Error · Download failed", failed.metadataValue)
        assertEquals("Retry", failed.trailing)
        assertTrue(failed.showPlayAction)
        assertEquals(listOf(failed.id), filterDownloadCards(listOf(failed), DownloadCategory.All, "").map { it.id })
        assertEquals(listOf(failed.id), filterDownloadCards(listOf(failed), DownloadCategory.Queued, "").map { it.id })
        assertTrue(filterDownloadCards(listOf(failed), DownloadCategory.Completed, "").isEmpty())
    }

    @Test
    fun everyDownloadStateLandsInExactlyOneOpenDesignCategory() {
        val expected = mapOf(
            DownloadState.QUEUED to DownloadCategory.Queued,
            DownloadState.COMPLETED to DownloadCategory.Completed,
            DownloadState.CONNECTING to DownloadCategory.Queued,
            DownloadState.DOWNLOADING to DownloadCategory.Queued,
            DownloadState.PAUSED to DownloadCategory.Queued,
            DownloadState.FAILED to DownloadCategory.Queued,
            DownloadState.CANCELLED to DownloadCategory.Queued,
        )
        assertEquals(DownloadState.entries.toSet(), expected.keys)

        assertEquals(
            listOf(DownloadCategory.All, DownloadCategory.Queued, DownloadCategory.Completed),
            DownloadCategory.entries.toList(),
        )

        DownloadState.entries.forEach { state ->
            val card = mapDownloadToCard(recordFor(state), nowEpochMillis = 2_000L)
            assertEquals(expected.getValue(state), card.category)
            assertEquals(listOf(card.id), filterDownloadCards(listOf(card), DownloadCategory.All, "").map { it.id })
            downloadStatusCategories.forEach { category ->
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

        assertEquals(listOf("queued-dune", "active-dune"), filterDownloadCards(cards, DownloadCategory.All, "dune").map { it.id })
        assertEquals(listOf("queued-dune", "active-dune"), filterDownloadCards(cards, DownloadCategory.Queued, "dune").map { it.id })
        assertEquals(listOf("queued-dune", "active-dune"), filterDownloadCards(cards, DownloadCategory.Queued, "DUNE").map { it.id })
        assertTrue(filterDownloadCards(cards, DownloadCategory.Completed, "dune").isEmpty())
        assertTrue(filterDownloadCards(cards, DownloadCategory.Queued, "no-such-file").isEmpty())
        assertTrue(filterDownloadCards(cards, DownloadCategory.All, "no-such-file").isEmpty())
    }

    @Test
    fun filteringReflectsStateTransitionImmediately() {
        var live = record(id = "moving", state = DownloadState.QUEUED)
        fun visible(category: DownloadCategory, query: String = "") =
            filterDownloadCards(listOf(mapDownloadToCard(live, nowEpochMillis = 2_000L)), category, query).map { it.id }

        assertEquals(listOf("moving"), visible(DownloadCategory.All))
        assertEquals(listOf("moving"), visible(DownloadCategory.Queued))
        assertTrue(visible(DownloadCategory.Completed).isEmpty())

        live = live.copy(state = DownloadState.DOWNLOADING, downloadedBytes = 500L, startedAtEpochMillis = 1_000L)
        assertEquals(listOf("moving"), visible(DownloadCategory.All, "MOV"))
        assertEquals(listOf("moving"), visible(DownloadCategory.Queued, "MOV"))
        assertTrue(visible(DownloadCategory.Completed).isEmpty())

        live = live.copy(
            state = DownloadState.COMPLETED,
            downloadedBytes = 1_000L,
            completedAtEpochMillis = 3_000L,
        )
        val current = listOf(mapDownloadToCard(live, nowEpochMillis = 3_000L))
        assertEquals(listOf("moving"), filterDownloadCards(current, DownloadCategory.All, "mov").map { it.id })
        assertEquals(listOf("moving"), filterDownloadCards(current, DownloadCategory.Completed, "mov").map { it.id })
        assertTrue(filterDownloadCards(current, DownloadCategory.Queued, "").isEmpty())
        assertEquals(
            listOf("moving"),
            downloadStatusCategories.flatMap { filterDownloadCards(current, it, "") }.map { it.id },
        )
    }

    @Test
    fun failedCardsShowClassifiedLabelAndRetryForEveryCategory() {
        val samples = listOf(
            "Browser session expired. Sign in again to continue." to DownloadFailure.BROWSER_SESSION_EXPIRED,
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
            assertEquals("Error · ${failure.label}", card.metadataValue)
            assertEquals(if (failure == DownloadFailure.BROWSER_SESSION_EXPIRED) "Sign in again" else "Retry", card.trailing)
            assertTrue(card.showPlayAction)
            assertEquals(DownloadCategory.Queued, card.category)
            assertEquals(listOf(card.id), filterDownloadCards(listOf(card), DownloadCategory.Queued, "").map { it.id })
            assertTrue(filterDownloadCards(listOf(card), DownloadCategory.Completed, "").isEmpty())
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
        assertEquals("<1 KB/s", formatCardSpeed(500L))
        assertEquals("500 KB/s", formatCardSpeed(500_000L))
        assertEquals("7 MB/s", formatCardSpeed((6.2 * 1024 * 1024).toLong()))
        assertEquals("13 MB/s", formatCardSpeed((12.4 * 1024 * 1024).toLong()))
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

    @Test
    fun allTabShowsEveryVisibleCardAndCountsExcludeHiddenCompleted() {
        val completed = mapDownloadToCard(recordFor(DownloadState.COMPLETED), 3_000L)
        val paused = mapDownloadToCard(recordFor(DownloadState.PAUSED), 3_000L)
        val queued = mapDownloadToCard(recordFor(DownloadState.QUEUED), 3_000L)
        val cards = listOf(completed, paused, queued)
        val visible = visibleDownloadCards(cards, setOf(completed.id))

        assertEquals(3, downloadTabCounts(cards).getValue(DownloadCategory.All))
        assertEquals(1, downloadTabCounts(cards).getValue(DownloadCategory.Completed))
        assertEquals(listOf(paused.id, queued.id), filterDownloadCards(visible, DownloadCategory.All, "").map { it.id })
        assertEquals(
            mapOf(
                DownloadCategory.All to 2,
                DownloadCategory.Queued to 2,
                DownloadCategory.Completed to 0,
            ),
            downloadTabCounts(visible),
        )
        assertEquals("All 2", downloadTabTitle(DownloadCategory.All, 2))
        assertEquals("Queue 1", downloadTabTitle(DownloadCategory.Queued, 1))
        assertEquals("Completed 0", downloadTabTitle(DownloadCategory.Completed, 0))
        assertEquals("No downloads", emptyDownloadsTitle(DownloadCategory.All))
        assertEquals("Downloads you add will appear here.", emptyDownloadsDescription(DownloadCategory.All))
        assertEquals("No completed downloads", emptyDownloadsTitle(DownloadCategory.Completed))
        assertEquals("Finished files will appear here.", emptyDownloadsDescription(DownloadCategory.Completed))
    }

    @Test fun allAndQueueIncludePausedFailedAndActiveWhileCompletedStaysSeparate() {
        val cards = listOf(
            mapDownloadToCard(recordFor(DownloadState.COMPLETED), 3_000L),
            mapDownloadToCard(recordFor(DownloadState.PAUSED), 3_000L),
            mapDownloadToCard(recordFor(DownloadState.FAILED), 3_000L),
            mapDownloadToCard(recordFor(DownloadState.DOWNLOADING), 3_000L),
        )
        val ordered = orderDownloadCards(cards)
        assertEquals(listOf("paused", "failed", "downloading", "completed"), ordered.map { it.id })
        assertEquals(listOf("paused", "failed", "downloading"),
            filterDownloadCards(ordered, DownloadCategory.Queued, "").map { it.id })
        assertEquals(listOf("paused", "failed", "downloading", "completed"),
            filterDownloadCards(ordered, DownloadCategory.All, "").map { it.id })
        assertEquals(3, downloadTabCounts(ordered).getValue(DownloadCategory.Queued))
    }

    @Test fun dragRequiresExplicitReorderModeInAllOrQueue() {
        val paused = mapDownloadToCard(recordFor(DownloadState.PAUSED), 3_000L)
        val failed = mapDownloadToCard(recordFor(DownloadState.FAILED), 3_000L)
        val completed = mapDownloadToCard(recordFor(DownloadState.COMPLETED), 3_000L)
        listOf(paused, failed).forEach { card ->
            assertFalse(canReorderDownloadCard(card, DownloadCategory.All, "", false, false))
            assertTrue(canReorderDownloadCard(card, DownloadCategory.All, "", false, true))
            assertTrue(canReorderDownloadCard(card, DownloadCategory.Queued, "", false, true))
            assertFalse(canReorderDownloadCard(card, DownloadCategory.All, "", true, true))
            assertFalse(canReorderDownloadCard(card, DownloadCategory.All, "name", false, true))
        }
        assertFalse(canReorderDownloadCard(completed, DownloadCategory.All, "", false, true))
    }
}
