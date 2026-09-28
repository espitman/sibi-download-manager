package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsSelectionTest {
    @Test
    fun pauseAppliesOnlyToConnectingAndDownloading() {
        DownloadState.entries.forEach { state ->
            val eligible = state == DownloadState.CONNECTING || state == DownloadState.DOWNLOADING
            assertEquals(state.name, eligible, downloadsSelectionPauseEligible(state))
        }
    }

    @Test
    fun resumeStartsQueuedAndRetriesPausedFailedCancelled() {
        assertEquals(DownloadsSelectionResumeKind.StartQueued, downloadsSelectionResumeKind(DownloadState.QUEUED))
        assertEquals(DownloadsSelectionResumeKind.ResumeOrRetry, downloadsSelectionResumeKind(DownloadState.PAUSED))
        assertEquals(DownloadsSelectionResumeKind.ResumeOrRetry, downloadsSelectionResumeKind(DownloadState.FAILED))
        assertEquals(DownloadsSelectionResumeKind.ResumeOrRetry, downloadsSelectionResumeKind(DownloadState.CANCELLED))
        assertEquals(DownloadsSelectionResumeKind.None, downloadsSelectionResumeKind(DownloadState.CONNECTING))
        assertEquals(DownloadsSelectionResumeKind.None, downloadsSelectionResumeKind(DownloadState.DOWNLOADING))
        assertEquals(DownloadsSelectionResumeKind.None, downloadsSelectionResumeKind(DownloadState.COMPLETED))
    }

    @Test
    fun mixedStatusesYieldOnlyEligiblePauseAndResumeIds() {
        val records = listOf(
            download("queued", DownloadState.QUEUED),
            download("connecting", DownloadState.CONNECTING),
            download("downloading", DownloadState.DOWNLOADING),
            download("paused", DownloadState.PAUSED),
            download("failed", DownloadState.FAILED),
            download("cancelled", DownloadState.CANCELLED),
            download("completed", DownloadState.COMPLETED),
        )
        val selected = records.map { it.id }.toSet()

        assertEquals(listOf("connecting", "downloading"), downloadsSelectionPauseIds(records, selected))
        assertEquals(listOf("queued"), downloadsSelectionStartQueuedIds(records, selected))
        assertEquals(listOf("paused", "failed", "cancelled"), downloadsSelectionResumeOrRetryIds(records, selected))
        assertTrue("completed" !in downloadsSelectionPauseIds(records, selected))
        assertTrue("completed" !in downloadsSelectionStartQueuedIds(records, selected))
        assertTrue("completed" !in downloadsSelectionResumeOrRetryIds(records, selected))
    }

    @Test
    fun pauseAndResumeIgnoreUnselectedAndUnknownIds() {
        val records = listOf(
            download("active", DownloadState.DOWNLOADING),
            download("queued", DownloadState.QUEUED),
            download("paused", DownloadState.PAUSED),
        )

        assertTrue(downloadsSelectionPauseIds(records, setOf("queued", "missing")).isEmpty())
        assertEquals(listOf("queued"), downloadsSelectionStartQueuedIds(records, setOf("queued", "active")))
        assertTrue(downloadsSelectionResumeOrRetryIds(records, setOf("active")).isEmpty())
    }

    @Test
    fun toggleAndPrunePreserveIdsAcrossTabsAndDropRemovedOrHidden() {
        val selected = toggleDownloadsSelection(emptySet(), "queued")
        val withCompleted = toggleDownloadsSelection(selected, "completed")
        val acrossTabs = setOf("queued", "completed", "paused")

        assertEquals(setOf("queued", "completed"), withCompleted)
        assertEquals(setOf("completed"), toggleDownloadsSelection(withCompleted, "queued"))
        assertEquals(
            setOf("queued", "completed", "paused"),
            pruneDownloadsSelection(
                acrossTabs,
                setOf("queued", "completed", "paused", "other-tab-only"),
            ),
        )
        assertEquals(
            setOf("queued", "paused"),
            pruneDownloadsSelection(acrossTabs, setOf("queued", "paused")),
        )
        assertTrue(pruneDownloadsSelection(acrossTabs, emptySet()).isEmpty())
    }

    @Test
    fun successfulActionsLeaveUnaffectedAndFailedIdsSelected() {
        val selected = setOf("paused", "completed", "downloading", "failed-delete")
        assertEquals(
            setOf("paused", "completed", "failed-delete"),
            removeActedOnDownloadsSelection(selected, listOf("downloading")),
        )
        assertEquals(
            setOf("paused", "failed-delete"),
            removeActedOnDownloadsSelection(selected, listOf("downloading", "completed")),
        )
        assertEquals(selected, removeActedOnDownloadsSelection(selected, emptyList()))
    }

    @Test
    fun deleteTargetsSplitIncompleteFromCompleted() {
        val records = listOf(
            download("queued", DownloadState.QUEUED),
            download("failed", DownloadState.FAILED),
            download("completed", DownloadState.COMPLETED),
            download("other-completed", DownloadState.COMPLETED),
        )
        val mixed = downloadsSelectionDeleteTargets(records, setOf("queued", "completed", "missing"))
        assertEquals(listOf("queued"), mixed.incompleteIds)
        assertEquals(listOf("completed"), mixed.completedIds)
        assertEquals(2, mixed.size)

        val incompleteOnly = downloadsSelectionDeleteTargets(records, setOf("queued", "failed"))
        assertEquals(listOf("queued", "failed"), incompleteOnly.incompleteIds)
        assertTrue(incompleteOnly.completedIds.isEmpty())
        assertFalse(incompleteOnly.isEmpty)
    }

    @Test
    fun deleteCopyMatchesSingleIncompleteCompletedAndMixedSemantics() {
        val incomplete = downloadsSelectionDeleteCopy(
            DownloadsSelectionDeleteTargets(incompleteIds = listOf("a"), completedIds = emptyList()),
        )
        assertEquals("Delete download?", incomplete.title)
        assertEquals(
            "This removes the download and its partial file. It cannot be resumed afterward.",
            incomplete.message,
        )
        assertEquals("Keep", incomplete.dismissLabel)
        assertEquals("Delete", incomplete.confirmLabel)
        assertEquals(null, incomplete.deleteFileLabel)

        val completedMany = downloadsSelectionDeleteCopy(
            DownloadsSelectionDeleteTargets(incompleteIds = emptyList(), completedIds = listOf("a", "b")),
        )
        assertEquals("Remove from Completed?", completedMany.title)
        assertEquals("Choose whether to keep the 2 downloaded files in Files.", completedMany.message)
        assertEquals("Keep in list", completedMany.dismissLabel)
        assertEquals("Remove only", completedMany.confirmLabel)
        assertEquals("Remove and delete files", completedMany.deleteFileLabel)

        val mixed = downloadsSelectionDeleteCopy(
            DownloadsSelectionDeleteTargets(incompleteIds = listOf("a"), completedIds = listOf("b")),
        )
        assertEquals("Delete selected downloads?", mixed.title)
        assertEquals("Remove only", mixed.confirmLabel)
        assertEquals("Remove and delete files", mixed.deleteFileLabel)
        assertTrue(mixed.message.contains("partial files"))
        assertTrue(mixed.message.contains("keep the files in Files"))
    }

    @Test
    fun pauseResumeAndDeleteToastsCoverEmptyMixedAndFailureCases() {
        assertEquals("1 selected", downloadsSelectionCountLabel(1))
        assertEquals("3 selected", downloadsSelectionCountLabel(3))
        assertEquals("No active downloads to pause", downloadsSelectionPauseToast(0))
        assertEquals("Download paused", downloadsSelectionPauseToast(1))
        assertEquals("2 downloads paused", downloadsSelectionPauseToast(2))
        assertEquals("No downloads to resume", downloadsSelectionResumeToast(0))
        assertEquals("Download resumed", downloadsSelectionResumeToast(1))
        assertEquals("Download and partial file deleted", downloadsSelectionDeleteToast(1, 0, 0, 0, false))
        assertEquals(
            "Removed from Completed. File remains in Files.",
            downloadsSelectionDeleteToast(0, 0, 1, 0, false),
        )
        assertEquals(
            "Completed downloads and files deleted",
            downloadsSelectionDeleteToast(0, 0, 2, 0, true),
        )
        assertEquals(
            "Selected downloads deleted. Completed files remain in Files.",
            downloadsSelectionDeleteToast(1, 0, 1, 0, false),
        )
        assertEquals("1 item could not be deleted. Please try again.", downloadsSelectionDeleteToast(1, 1, 0, 0, false))
        assertEquals("2 items could not be deleted. Please try again.", downloadsSelectionDeleteToast(0, 1, 0, 1, true))
    }

    private fun download(id: String, state: DownloadState) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        destinationPath = "/downloads/$id.zip",
        totalBytes = 1_000L,
        downloadedBytes = if (state == DownloadState.COMPLETED) 1_000L else 10L,
        state = state,
        error = if (state == DownloadState.FAILED) "Network error" else null,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = if (state == DownloadState.COMPLETED) 1_000L else null,
    )
}
