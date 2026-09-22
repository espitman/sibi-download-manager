package com.espitman.sdm.notification

import com.espitman.sdm.download.CancelTransferCommand
import com.espitman.sdm.download.DownloadTransferCommand
import com.espitman.sdm.download.DownloadTransferSession
import com.espitman.sdm.download.PauseTransferCommand
import com.espitman.sdm.download.ResumeTransferCommand
import com.espitman.sdm.download.SessionCommandResult
import com.espitman.sdm.download.StartTransferCommand
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferNotificationActionRoutingTest {
    @Test
    fun notificationActionsParseToTheSameCommandsAsInAppControls() {
        val id = "download-42"
        assertEquals(
            PauseTransferCommand(id),
            parseAction(TransferNotificationActionKind.PAUSE, id),
        )
        assertEquals(
            ResumeTransferCommand(id),
            parseAction(TransferNotificationActionKind.RESUME, id),
        )
        assertEquals(
            CancelTransferCommand(id),
            parseAction(TransferNotificationActionKind.CANCEL, id),
        )
        assertEquals(
            parseAction(TransferNotificationActionKind.PAUSE, "  $id  "),
            DownloadTransferCommand.parse(
                DownloadTransferCommand.ACTION_PAUSE_TRANSFER,
                id,
                tempFilePath = null,
            ),
        )
    }

    @Test
    fun identityExtrasMatchParsedDownloadIds() {
        TransferNotificationActionKind.entries.forEach { kind ->
            val action = TransferNotificationActions.serviceAction(kind)
            val identity = TransferNotificationPendingIntentSpec.identity("dl-9", action)!!
            val parsed = DownloadTransferCommand.parse(identity.serviceAction, identity.downloadId, null)
            assertEquals(identity.downloadId, parsed?.downloadId)
            assertEquals(action, identity.serviceAction)
        }
    }

    @Test
    fun invalidMissingIdsAndUnknownActionsAreInert() {
        assertNull(parseAction(TransferNotificationActionKind.PAUSE, null))
        assertNull(parseAction(TransferNotificationActionKind.RESUME, ""))
        assertNull(parseAction(TransferNotificationActionKind.CANCEL, "   "))
        assertNull(
            DownloadTransferCommand.parse(
                TransferNotificationCoordinator.ACTION_OPEN_DOWNLOAD,
                "download-42",
                tempFilePath = null,
            ),
        )
        assertNull(
            DownloadTransferCommand.parse(
                "com.espitman.sdm.download.action.UNKNOWN",
                "download-42",
                tempFilePath = null,
            ),
        )
        val session = DownloadTransferSession()
        assertEquals(SessionCommandResult.None, session.handleCommand(1, command = null))
        assertEquals(1, session.startIdIfIdle())
        assertFalse(session.isPauseRequested("missing"))
        assertFalse(session.isCancelRequested("missing"))
    }

    @Test
    fun duplicatePauseResumeCancelThroughTheSameSessionStayIdempotent() {
        val session = DownloadTransferSession()
        val start = StartTransferCommand("dl-1", "/tmp/a.part")
        val job = Job()
        session.handleCommand(1, start)
        session.attachJob(start.downloadId, job)

        val pause = parseAction(TransferNotificationActionKind.PAUSE, start.downloadId)
        assertEquals(SessionCommandResult.CancelJob(start.downloadId, job), session.handleCommand(2, pause))
        assertEquals(SessionCommandResult.CancelJob(start.downloadId, job), session.handleCommand(3, pause))
        assertTrue(session.isPauseRequested(start.downloadId))
        assertNull(session.startIdIfIdle())

        val cancel = parseAction(TransferNotificationActionKind.CANCEL, start.downloadId)
        assertEquals(SessionCommandResult.CancelJob(start.downloadId, job), session.handleCommand(4, cancel))
        assertEquals(SessionCommandResult.None, session.handleCommand(5, cancel))
        assertTrue(session.isCancelRequested(start.downloadId))
        assertFalse(session.isPauseRequested(start.downloadId))

        session.onTransferFinished(start.downloadId)

        val resume = parseAction(TransferNotificationActionKind.RESUME, start.downloadId)
        assertEquals(SessionCommandResult.StartJob(resume!!), session.handleCommand(6, resume))
        assertEquals(SessionCommandResult.None, session.handleCommand(7, resume))
        assertEquals(SessionCommandResult.None, session.handleCommand(8, start))
        assertNull(session.startIdIfIdle())
        assertEquals(8, session.onTransferFinished(start.downloadId))
    }

    private fun parseAction(kind: TransferNotificationActionKind, downloadId: String?) =
        DownloadTransferCommand.parse(
            action = TransferNotificationActions.serviceAction(kind),
            downloadId = downloadId,
            tempFilePath = null,
        )
}
