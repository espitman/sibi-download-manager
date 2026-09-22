package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.ChecksumVerificationResult
import com.espitman.sdm.download.CompletedFileDeleteResult
import com.espitman.sdm.download.DownloadRenameResult
import com.espitman.sdm.storage.CompletedFileUserMessages
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
    fun deleteMessagesCloseOnlyAfterStorageAndRecordAgree() {
        assertEquals(
            CompletedFileUserMessages.DELETED,
            completedFileDeleteActionMessage(CompletedFileDeleteResult.Deleted()),
        )
        assertEquals(
            CompletedFileUserMessages.ALREADY_DELETED,
            completedFileDeleteActionMessage(CompletedFileDeleteResult.AlreadyMissing()),
        )
        assertEquals(
            CompletedFileUserMessages.ACCESS_UNAVAILABLE,
            completedFileDeleteActionMessage(
                CompletedFileDeleteResult.Failure(CompletedFileUserMessages.ACCESS_UNAVAILABLE),
            ),
        )
        assertTrue(shouldCloseDeleteDialog(CompletedFileDeleteResult.Deleted()))
        assertTrue(shouldCloseDeleteDialog(CompletedFileDeleteResult.AlreadyMissing()))
        assertFalse(
            shouldCloseDeleteDialog(
                CompletedFileDeleteResult.Failure(CompletedFileUserMessages.DELETE_FAILED),
            ),
        )
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

    @Test
    fun checksumToastsUseTypedVerifierMessages() {
        assertEquals(
            "No reference checksum available",
            checksumVerificationMessage(ChecksumVerificationResult.NoReference),
        )
        assertEquals(
            "Complete the download before verification",
            checksumVerificationMessage(ChecksumVerificationResult.NotCompleted),
        )
        assertEquals(
            "Downloaded file is missing",
            checksumVerificationMessage(ChecksumVerificationResult.MissingFile),
        )
        assertEquals(
            "Checksum verified",
            checksumVerificationMessage(ChecksumVerificationResult.Match),
        )
        assertEquals(
            "Checksum mismatch",
            checksumVerificationMessage(ChecksumVerificationResult.Mismatch),
        )
        assertEquals(
            "Could not verify checksum",
            checksumVerificationMessage(ChecksumVerificationResult.Failure),
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
