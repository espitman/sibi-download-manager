package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadRenameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadDetailsActionsTest {
    @Test
    fun renameMessagesUseCoordinatorTextAndPrototypeSuccess() {
        val success = Download(
            id = "doc",
            url = "https://example.com/doc.pdf",
            fileName = "next.pdf",
            destinationPath = "/Download/next.pdf",
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
        )
        assertEquals(
            "Download renamed",
            downloadRenameActionMessage(DownloadRenameResult.Success(success)),
        )
        assertEquals(
            "Filename cannot be blank",
            downloadRenameActionMessage(DownloadRenameResult.Failure("Filename cannot be blank")),
        )
        assertEquals(
            "Pause the download before renaming",
            downloadRenameActionMessage(DownloadRenameResult.PauseRequired()),
        )
        assertEquals(
            "Wait until the transfer pauses",
            downloadRenameActionMessage(DownloadRenameResult.PauseRequired("Wait until the transfer pauses")),
        )
        assertTrue(shouldCloseRenameDialog(DownloadRenameResult.Success(success)))
        assertFalse(shouldCloseRenameDialog(DownloadRenameResult.Failure("Filename is unsafe")))
        assertFalse(shouldCloseRenameDialog(DownloadRenameResult.PauseRequired()))
    }

    @Test
    fun moveToTopToastsOnlyWhenQueuedRecordChanges() {
        val queued = record(id = "q", state = DownloadState.QUEUED, sortOrder = 4, updatedAt = 1_000L)
        val moved = queued.copy(sortOrder = 0, updatedAtEpochMillis = 2_000L)
        val alreadyFirst = queued.copy(sortOrder = 0)
        val paused = record(id = "p", state = DownloadState.PAUSED, sortOrder = 4, updatedAt = 1_000L)

        assertEquals("Download moved to top", moveToTopActionMessage(queued, moved))
        assertEquals("Already at the top", moveToTopActionMessage(alreadyFirst, alreadyFirst))
        assertEquals("Already at the top", moveToTopActionMessage(queued, queued))
        assertEquals("Only queued downloads can be moved", moveToTopActionMessage(paused, paused))
        assertEquals("Only queued downloads can be moved", moveToTopActionMessage(queued, null))
        assertEquals(
            "Only queued downloads can be moved",
            moveToTopActionMessage(queued, queued.copy(state = DownloadState.DOWNLOADING)),
        )
    }

    private fun record(
        id: String,
        state: DownloadState,
        sortOrder: Long,
        updatedAt: Long,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        state = state,
        sortOrder = sortOrder,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = updatedAt,
    )
}
