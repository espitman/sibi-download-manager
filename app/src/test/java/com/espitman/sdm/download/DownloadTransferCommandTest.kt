package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTransferCommandTest {
    @Test
    fun parsesCompleteStartTransferCommandsAndTrimsFields() {
        val parsed = DownloadTransferCommand.parse(
            action = DownloadTransferCommand.ACTION_START_TRANSFER,
            downloadId = "  download-1  ",
            tempFilePath = "  /tmp/.sdm-abc.part  ",
        )
        assertEquals(StartTransferCommand("download-1", "/tmp/.sdm-abc.part"), parsed)
    }

    @Test
    fun parsesPauseTransferCommandsKeyedByDownloadId() {
        val parsed = DownloadTransferCommand.parse(
            action = DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
            downloadId = "  download-1  ",
            tempFilePath = null,
        )
        assertEquals(PauseTransferCommand("download-1"), parsed)
        assertEquals(
            PauseTransferCommand("download-1"),
            DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
                downloadId = "download-1",
                tempFilePath = "/ignored/path.part",
            ),
        )
    }

    @Test
    fun rejectsMissingBlankOrMismatchedStartTransferCommands() {
        val validAction = DownloadTransferCommand.ACTION_START_TRANSFER
        val cases = listOf(
            Triple(null, "id", "/tmp/file.part"),
            Triple("com.espitman.sdm.download.action.OTHER", "id", "/tmp/file.part"),
            Triple(validAction, null, "/tmp/file.part"),
            Triple(validAction, "", "/tmp/file.part"),
            Triple(validAction, "   ", "/tmp/file.part"),
            Triple(validAction, "id", null),
            Triple(validAction, "id", ""),
            Triple(validAction, "id", "   "),
            Triple(null, null, null),
        )
        cases.forEach { (action, downloadId, tempFilePath) ->
            assertNull(
                "Should reject action=$action id=$downloadId path=$tempFilePath",
                DownloadTransferCommand.parse(action, downloadId, tempFilePath),
            )
        }
    }

    @Test
    fun rejectsPauseCommandsWithoutADownloadId() {
        val action = DownloadTransferCommand.ACTION_PAUSE_TRANSFER
        listOf(null, "", "   ").forEach { downloadId ->
            assertNull(
                DownloadTransferCommand.parse(action, downloadId, tempFilePath = null),
            )
        }
        assertNull(
            DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_START_TRANSFER,
                downloadId = "id",
                tempFilePath = null,
            ),
        )
    }

    @Test
    fun parsesResumeTransferCommandsKeyedByDownloadId() {
        val parsed = DownloadTransferCommand.parse(
            action = DownloadTransferCommand.ACTION_RESUME_TRANSFER,
            downloadId = "  download-1  ",
            tempFilePath = null,
        )
        assertEquals(ResumeTransferCommand("download-1"), parsed)
        assertEquals(
            ResumeTransferCommand("download-1"),
            DownloadTransferCommand.parse(
                action = DownloadTransferCommand.ACTION_RESUME_TRANSFER,
                downloadId = "download-1",
                tempFilePath = "/ignored/path.part",
            ),
        )
    }

    @Test
    fun rejectsResumeCommandsWithoutADownloadId() {
        val action = DownloadTransferCommand.ACTION_RESUME_TRANSFER
        listOf(null, "", "   ").forEach { downloadId ->
            assertNull(
                DownloadTransferCommand.parse(action, downloadId, tempFilePath = null),
            )
        }
    }
}
