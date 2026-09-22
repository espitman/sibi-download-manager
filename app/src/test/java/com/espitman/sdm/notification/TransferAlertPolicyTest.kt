package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferAlertPolicyTest {
    @Test
    fun completionSeedsHistoryAndPostsOnlyOneEnabledTransition() {
        val tracker = CompletionAlertTracker()
        assertTrue(tracker.observe(listOf(record("old", DownloadState.COMPLETED, 10)), true).toPost.isEmpty())
        assertTrue(tracker.observe(listOf(record("old", DownloadState.COMPLETED, 10)), true).toPost.isEmpty())

        tracker.observe(
            listOf(record("old", DownloadState.COMPLETED, 10), record("live", DownloadState.DOWNLOADING, 4)),
            true,
        )
        assertEquals(
            listOf("live"),
            tracker.observe(
                listOf(record("old", DownloadState.COMPLETED, 10), record("live", DownloadState.COMPLETED, 10)),
                true,
            ).toPost.map { it.id },
        )
        assertTrue(
            tracker.observe(listOf(record("live", DownloadState.COMPLETED, 10)), true).toPost.isEmpty(),
        )
    }

    @Test
    fun disabledCompletionIsNotReplayedWhenToggleTurnsOn() {
        val tracker = CompletionAlertTracker()
        tracker.observe(listOf(record("a", DownloadState.DOWNLOADING, 4)), enabled = true)
        assertTrue(tracker.observe(listOf(record("a", DownloadState.COMPLETED, 10)), enabled = false).toPost.isEmpty())
        assertTrue(tracker.observe(listOf(record("a", DownloadState.COMPLETED, 10)), enabled = true).toPost.isEmpty())
    }

    @Test
    fun stallPostsAtThresholdOnceThenCancelsOnProgressAndCanPostAgain() {
        val tracker = StallAlertTracker(thresholdMs = 30_000)
        val active = record("a", DownloadState.DOWNLOADING, 4)
        assertEquals(StallAlertPlan(), tracker.observe(listOf(active), true, 1_000))
        assertTrue(tracker.observe(listOf(active), true, 30_999).toPost.isEmpty())
        assertEquals(listOf("a"), tracker.observe(listOf(active), true, 31_000).toPost.map { it.id })
        assertTrue(tracker.observe(listOf(active), true, 61_000).toPost.isEmpty())

        val progressed = active.copy(downloadedBytes = 5)
        assertEquals(setOf("a"), tracker.observe(listOf(progressed), true, 62_000).idsToCancel)
        assertTrue(tracker.observe(listOf(progressed), true, 91_999).toPost.isEmpty())
        assertEquals(listOf("a"), tracker.observe(listOf(progressed), true, 92_000).toPost.map { it.id })
    }

    @Test
    fun stallCancelsOnToggleStateChangeAndRemoval() {
        val tracker = StallAlertTracker(thresholdMs = 10)
        val a = record("a", DownloadState.CONNECTING, 0)
        tracker.observe(listOf(a), true, 0)
        tracker.observe(listOf(a), true, 10)
        assertEquals(setOf("a"), tracker.observe(listOf(a), false, 11).idsToCancel)

        tracker.observe(listOf(a), true, 20)
        tracker.observe(listOf(a), true, 30)
        assertEquals(
            setOf("a"),
            tracker.observe(listOf(a.copy(state = DownloadState.PAUSED)), true, 31).idsToCancel,
        )

        tracker.observe(listOf(a), true, 40)
        tracker.observe(listOf(a), true, 50)
        assertEquals(setOf("a"), tracker.observe(emptyList(), true, 51).idsToCancel)
    }

    @Test
    fun queuedPausedAndTerminalRecordsNeverStartStallWatches() {
        val tracker = StallAlertTracker(thresholdMs = 1)
        val states = listOf(
            DownloadState.QUEUED,
            DownloadState.PAUSED,
            DownloadState.COMPLETED,
            DownloadState.FAILED,
            DownloadState.CANCELLED,
        )
        val records = states.mapIndexed { index, state -> record("$index", state, if (state == DownloadState.COMPLETED) 10 else 0) }
        tracker.observe(records, true, 0)
        assertEquals(StallAlertPlan(), tracker.observe(records, true, 100))
    }

    private fun record(id: String, state: DownloadState, bytes: Long): Download = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = "$id.bin",
        totalBytes = 10,
        downloadedBytes = bytes,
        state = state,
        error = if (state == DownloadState.FAILED) "failed" else null,
        createdAtEpochMillis = 1,
        updatedAtEpochMillis = 1,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 2 else null,
    )
}
