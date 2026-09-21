package com.espitman.sdm.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownloadTransferSessionTest {
    @Test
    fun invalidCommandWhileIdleRequestsStopWithThatStartId() {
        val session = DownloadTransferSession()

        assertNull(session.handleCommand(startId = 7, command = null))
        assertEquals(7, session.startIdIfIdle())
    }

    @Test
    fun duplicateDownloadIdDoesNotStartASecondJobOrRequestStop() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")

        assertEquals(command, session.handleCommand(1, command))
        assertNull(session.handleCommand(2, command))
        assertNull(session.handleCommand(3, command.copy(tempFilePath = "/tmp/other.part")))
        assertNull(session.startIdIfIdle())

        assertEquals(3, session.onTransferFinished(command.downloadId))
    }

    @Test
    fun staysRunningUntilTheLastDistinctTransferFinishes() {
        val session = DownloadTransferSession()
        val first = StartTransferCommand("dl-1", "/tmp/a.part")
        val second = StartTransferCommand("dl-2", "/tmp/b.part")

        assertEquals(first, session.handleCommand(1, first))
        assertEquals(second, session.handleCommand(2, second))
        assertNull(session.handleCommand(3, null))
        assertNull(session.startIdIfIdle())

        assertNull(session.onTransferFinished(first.downloadId))
        assertEquals(3, session.onTransferFinished(second.downloadId))
    }

    @Test
    fun finishingTheLastJobUsesTheLatestStartIdSoANewerStartIsNotStopped() {
        val session = DownloadTransferSession()
        val first = StartTransferCommand("dl-1", "/tmp/a.part")
        val second = StartTransferCommand("dl-2", "/tmp/b.part")

        assertEquals(first, session.handleCommand(1, first))
        assertEquals(second, session.handleCommand(2, second))
        assertNull(session.onTransferFinished(second.downloadId))
        assertEquals(2, session.onTransferFinished(first.downloadId))
    }

    @Test
    fun sameDownloadIdCanStartAgainAfterItFinishes() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")

        assertEquals(command, session.handleCommand(1, command))
        assertEquals(1, session.onTransferFinished(command.downloadId))
        assertEquals(1, session.startIdIfIdle())

        assertEquals(command, session.handleCommand(2, command))
        assertNull(session.startIdIfIdle())
        assertEquals(2, session.onTransferFinished(command.downloadId))
    }
}
