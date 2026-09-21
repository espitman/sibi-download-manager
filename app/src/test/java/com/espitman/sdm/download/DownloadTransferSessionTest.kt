package com.espitman.sdm.download

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadTransferSessionTest {
    @Test
    fun invalidCommandWhileIdleRequestsStopWithThatStartId() {
        val session = DownloadTransferSession()

        assertEquals(SessionCommandResult.None, session.handleCommand(startId = 7, command = null))
        assertEquals(7, session.startIdIfIdle())
    }

    @Test
    fun duplicateDownloadIdDoesNotStartASecondJobOrRequestStop() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")

        assertEquals(SessionCommandResult.StartJob(command), session.handleCommand(1, command))
        assertEquals(SessionCommandResult.None, session.handleCommand(2, command))
        assertEquals(SessionCommandResult.None, session.handleCommand(3, command.copy(tempFilePath = "/tmp/other.part")))
        assertNull(session.startIdIfIdle())

        assertEquals(3, session.onTransferFinished(command.downloadId))
    }

    @Test
    fun staysRunningUntilTheLastDistinctTransferFinishes() {
        val session = DownloadTransferSession()
        val first = StartTransferCommand("dl-1", "/tmp/a.part")
        val second = StartTransferCommand("dl-2", "/tmp/b.part")

        assertEquals(SessionCommandResult.StartJob(first), session.handleCommand(1, first))
        assertEquals(SessionCommandResult.StartJob(second), session.handleCommand(2, second))
        assertEquals(SessionCommandResult.None, session.handleCommand(3, null))
        assertNull(session.startIdIfIdle())

        assertNull(session.onTransferFinished(first.downloadId))
        assertEquals(3, session.onTransferFinished(second.downloadId))
    }

    @Test
    fun finishingTheLastJobUsesTheLatestStartIdSoANewerStartIsNotStopped() {
        val session = DownloadTransferSession()
        val first = StartTransferCommand("dl-1", "/tmp/a.part")
        val second = StartTransferCommand("dl-2", "/tmp/b.part")

        assertEquals(SessionCommandResult.StartJob(first), session.handleCommand(1, first))
        assertEquals(SessionCommandResult.StartJob(second), session.handleCommand(2, second))
        assertNull(session.onTransferFinished(second.downloadId))
        assertEquals(2, session.onTransferFinished(first.downloadId))
    }

    @Test
    fun sameDownloadIdCanStartAgainAfterItFinishes() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")

        assertEquals(SessionCommandResult.StartJob(command), session.handleCommand(1, command))
        assertEquals(1, session.onTransferFinished(command.downloadId))
        assertEquals(1, session.startIdIfIdle())

        assertEquals(SessionCommandResult.StartJob(command), session.handleCommand(2, command))
        assertNull(session.startIdIfIdle())
        assertEquals(2, session.onTransferFinished(command.downloadId))
    }

    @Test
    fun pauseCancelsTheAttachedJobAndKeepsTheSessionAliveUntilFinish() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")
        val job = Job()

        session.handleCommand(1, command)
        assertFalse(session.attachJob(command.downloadId, job))
        assertNull(session.startIdIfIdle())

        val pause = session.handleCommand(2, PauseTransferCommand(command.downloadId))
        check(pause is SessionCommandResult.CancelJob)
        assertEquals(command.downloadId, pause.downloadId)
        assertSame(job, pause.job)
        assertTrue(session.isPauseRequested(command.downloadId))
        assertEquals("/tmp/a.part", session.tempFilePath(command.downloadId))
        assertNull(session.startIdIfIdle())

        val duplicate = session.handleCommand(3, PauseTransferCommand(command.downloadId))
        check(duplicate is SessionCommandResult.CancelJob)
        assertSame(job, duplicate.job)
        assertNull(session.startIdIfIdle())

        assertEquals(3, session.onTransferFinished(command.downloadId))
        assertFalse(session.isPauseRequested(command.downloadId))
    }

    @Test
    fun pauseBeforeAttachMarksTheJobSoTheCallerCancelsAfterLaunch() {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("dl-1", "/tmp/a.part")
        session.handleCommand(1, command)

        assertEquals(
            SessionCommandResult.None,
            session.handleCommand(2, PauseTransferCommand(command.downloadId)),
        )
        assertTrue(session.isPauseRequested(command.downloadId))
        assertNull(session.startIdIfIdle())

        val job = Job()
        assertTrue(session.attachJob(command.downloadId, job))
        assertNull(session.startIdIfIdle())
        assertEquals(2, session.onTransferFinished(command.downloadId))
    }

    @Test
    fun pauseForMissingCompletedOrIdleIdsDoesNotCorruptActiveWork() {
        val session = DownloadTransferSession()
        val active = StartTransferCommand("active", "/tmp/a.part")
        session.handleCommand(1, active)
        session.attachJob(active.downloadId, Job())

        assertEquals(SessionCommandResult.None, session.handleCommand(2, PauseTransferCommand("missing")))
        assertEquals(SessionCommandResult.None, session.handleCommand(3, PauseTransferCommand("paused-already")))
        assertNull(session.startIdIfIdle())

        session.onTransferFinished(active.downloadId)
        assertEquals(SessionCommandResult.None, session.handleCommand(4, PauseTransferCommand(active.downloadId)))
        assertEquals(4, session.startIdIfIdle())
    }

    @Test
    fun duplicateResumeDoesNotStartASecondJobOrRequestStop() {
        val session = DownloadTransferSession()
        val resume = ResumeTransferCommand("dl-1")
        val start = StartTransferCommand("dl-1", "/tmp/a.part")

        assertEquals(SessionCommandResult.StartJob(resume), session.handleCommand(1, resume))
        assertEquals(SessionCommandResult.None, session.handleCommand(2, resume))
        assertEquals(SessionCommandResult.None, session.handleCommand(3, start))
        assertNull(session.startIdIfIdle())

        val pause = session.handleCommand(4, PauseTransferCommand("dl-1"))
        assertEquals(SessionCommandResult.None, pause)
        assertTrue(session.isPauseRequested("dl-1"))

        assertEquals(4, session.onTransferFinished("dl-1"))
        assertEquals(SessionCommandResult.StartJob(resume), session.handleCommand(5, resume))
        assertEquals(5, session.onTransferFinished("dl-1"))
    }
}
