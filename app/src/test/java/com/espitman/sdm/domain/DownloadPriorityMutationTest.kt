package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadPriorityMutationTest {
    @Test
    fun togglesQueuedBetweenNormalAndHighWithMonotonicTime() {
        val queued = download(DownloadState.QUEUED)
        val high = DownloadPriorityMutation.toggle(queued, nowEpochMillis = 500)
        assertEquals(DownloadPriorityMutation.HIGH, high.priority)
        assertEquals(1_000L, high.updatedAtEpochMillis)
        assertTrue(DownloadPriorityMutation.isHigh(high.priority))

        val normal = DownloadPriorityMutation.toggle(high, nowEpochMillis = 2_000)
        assertEquals(DownloadPriorityMutation.NORMAL, normal.priority)
        assertEquals(2_000L, normal.updatedAtEpochMillis)
        assertFalse(DownloadPriorityMutation.isHigh(normal.priority))
    }

    @Test
    fun allowsPausedConnectingDownloadingAndFailed() {
        val connecting = DownloadStateMachine.transition(download(DownloadState.QUEUED), DownloadState.CONNECTING, 2_000)
        val downloading = DownloadStateMachine.transition(connecting, DownloadState.DOWNLOADING, 3_000)
        val paused = DownloadStateMachine.transition(downloading.copy(downloadedBytes = 1), DownloadState.PAUSED, 4_000)
        val failed = download(DownloadState.QUEUED).copy(state = DownloadState.FAILED, error = "lost")

        assertEquals(DownloadPriorityMutation.HIGH, DownloadPriorityMutation.toggle(connecting, 3_000).priority)
        assertEquals(DownloadPriorityMutation.HIGH, DownloadPriorityMutation.toggle(downloading, 4_000).priority)
        assertEquals(DownloadPriorityMutation.HIGH, DownloadPriorityMutation.toggle(paused, 5_000).priority)
        assertEquals(DownloadPriorityMutation.HIGH, DownloadPriorityMutation.toggle(failed, 2_000).priority)
    }

    @Test
    fun completedAndCancelledAreUnchanged() {
        val completed = download(DownloadState.QUEUED).copy(
            state = DownloadState.COMPLETED,
            completedAtEpochMillis = 2_000,
        )
        val cancelled = DownloadStateMachine.transition(download(DownloadState.QUEUED), DownloadState.CANCELLED, 2_000)
        val highCancelled = cancelled.copy(priority = DownloadPriorityMutation.HIGH)

        assertSame(completed, DownloadPriorityMutation.toggle(completed, 9_000))
        assertSame(cancelled, DownloadPriorityMutation.toggle(cancelled, 9_000))
        assertSame(highCancelled, DownloadPriorityMutation.toggle(highCancelled, 9_000))
        assertEquals(2_000L, cancelled.updatedAtEpochMillis)
    }

    @Test
    fun settingTheSamePriorityIsIdempotent() {
        val queued = download(DownloadState.QUEUED)
        assertSame(queued, DownloadPriorityMutation.apply(queued, DownloadPriorityMutation.NORMAL, 9_000))
        val high = queued.copy(priority = DownloadPriorityMutation.HIGH)
        assertSame(high, DownloadPriorityMutation.apply(high, DownloadPriorityMutation.HIGH, 9_000))
    }

    @Test
    fun rejectsNegativePriority() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadPriorityMutation.apply(download(DownloadState.QUEUED), priority = -1, nowEpochMillis = 2_000)
        }
    }

    private fun download(state: DownloadState) = Download(
        id = "download-1",
        url = "https://example.com/file.bin",
        fileName = "file.bin",
        state = state,
        createdAtEpochMillis = 1_000,
        updatedAtEpochMillis = 1_000,
    )
}
