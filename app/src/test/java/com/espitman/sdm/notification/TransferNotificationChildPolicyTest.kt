package com.espitman.sdm.notification

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationChildPolicyTest {
    @Test
    fun postsConnectingDownloadingAndPausedChildrenOnly() {
        assertTrue(TransferNotificationChildPolicy.shouldPostChild(DownloadState.CONNECTING))
        assertTrue(TransferNotificationChildPolicy.shouldPostChild(DownloadState.DOWNLOADING))
        assertTrue(TransferNotificationChildPolicy.shouldPostChild(DownloadState.PAUSED))
        assertFalse(TransferNotificationChildPolicy.shouldPostChild(DownloadState.QUEUED))
        assertFalse(TransferNotificationChildPolicy.shouldPostChild(DownloadState.COMPLETED))
        assertFalse(TransferNotificationChildPolicy.shouldPostChild(DownloadState.FAILED))
        assertFalse(TransferNotificationChildPolicy.shouldPostChild(DownloadState.CANCELLED))
    }

    @Test
    fun liveReconcileCancelsTerminalMissingAndNonPostedRecords() {
        val posted = setOf("gone", "completed-now", "paused", "active")
        val system = setOf("orphaned-paused", "paused")
        val states = mapOf(
            "completed-now" to DownloadState.COMPLETED,
            "paused" to DownloadState.PAUSED,
            "active" to DownloadState.DOWNLOADING,
            "failed" to DownloadState.FAILED,
            "cancelled" to DownloadState.CANCELLED,
            "queued" to DownloadState.QUEUED,
        )
        assertEquals(
            setOf("gone", "completed-now", "orphaned-paused", "failed", "cancelled", "queued"),
            TransferNotificationChildPolicy.idsToCancel(posted, system, states),
        )
    }

    @Test
    fun teardownPostsCurrentPausedEvenWhenPostedStateIsStillActive() {
        val paused = record("was-active", DownloadState.PAUSED, downloadedBytes = 4L)
        val stillActive = record("still-downloading", DownloadState.DOWNLOADING, downloadedBytes = 2L)
        val completed = record(
            "done",
            DownloadState.COMPLETED,
            downloadedBytes = 10L,
            completedAt = 2_000L,
        )
        val plan = TransferNotificationChildPolicy.teardownPlan(
            postedTags = setOf("was-active", "still-downloading"),
            systemChildTags = setOf("was-active"),
            downloads = listOf(paused, stillActive, completed),
        )

        assertEquals(listOf("was-active"), plan.childrenToPost.map { it.id })
        assertEquals(DownloadState.PAUSED, plan.childrenToPost.single().state)
        assertEquals(setOf("still-downloading", "done"), plan.idsToCancel)
        assertFalse(plan.idsToCancel.contains("was-active"))
    }

    @Test
    fun teardownCancelsActiveTerminalAndMissingWhileKeepingSnapshotPaused() {
        val keptPaused = record("kept-paused", DownloadState.PAUSED, downloadedBytes = 3L)
        val connecting = record("connecting", DownloadState.CONNECTING)
        val failed = record("failed", DownloadState.FAILED)
        val plan = TransferNotificationChildPolicy.teardownPlan(
            postedTags = setOf("kept-paused", "connecting", "failed", "missing-posted"),
            systemChildTags = setOf("kept-paused", "deleted-paused"),
            downloads = listOf(keptPaused, connecting, failed),
        )

        assertEquals(listOf("kept-paused"), plan.childrenToPost.map { it.id })
        assertEquals(
            setOf("connecting", "failed", "missing-posted", "deleted-paused"),
            plan.idsToCancel,
        )
    }

    @Test
    fun childrenToPostPreservesOrderAndDropsTerminalRecords() {
        val downloads = listOf(
            record("queued", DownloadState.QUEUED),
            record("active", DownloadState.DOWNLOADING),
            record("paused", DownloadState.PAUSED),
            record("done", DownloadState.COMPLETED, downloadedBytes = 10L, completedAt = 2_000L),
        )
        assertEquals(
            listOf("active", "paused"),
            TransferNotificationChildPolicy.childrenToPost(downloads).map { it.id },
        )
    }

    @Test
    fun activeCountIgnoresPausedAndTerminalStates() {
        assertEquals(
            2,
            TransferNotificationChildPolicy.activeCount(
                listOf(
                    DownloadState.CONNECTING,
                    DownloadState.PAUSED,
                    DownloadState.DOWNLOADING,
                    DownloadState.COMPLETED,
                    DownloadState.FAILED,
                ),
            ),
        )
        assertEquals(
            0,
            TransferNotificationChildPolicy.activeCount(
                listOf(DownloadState.PAUSED, DownloadState.CANCELLED, DownloadState.QUEUED),
            ),
        )
    }

    private fun record(
        id: String,
        state: DownloadState,
        downloadedBytes: Long = 0L,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        destinationPath = "/downloads/$id.zip",
        totalBytes = 10L,
        downloadedBytes = downloadedBytes,
        state = state,
        error = if (state == DownloadState.FAILED) "failed" else null,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        startedAtEpochMillis = null,
        completedAtEpochMillis = completedAt,
    )
}
