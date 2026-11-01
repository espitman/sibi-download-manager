package com.espitman.sdm.download

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class TransferSlotCleanupTest {
    @Test
    fun cancelledTransferStillReleasesClaimAndSkipsRescheduleWhenServiceIsDead() = runBlocking {
        val counts = runCancelledTransferCleanup(acceptingWork = false)
        assertEquals(1, counts.released)
        assertEquals(0, counts.rescheduled)
    }

    @Test
    fun cancelledTransferReleasesClaimAndReschedulesWhileServiceStillAcceptsWork() = runBlocking {
        val counts = runCancelledTransferCleanup(acceptingWork = true)
        assertEquals(1, counts.released)
        assertEquals(1, counts.rescheduled)
    }

    @Test
    fun rejectedStartReleasesClaimEvenWhenTheSurroundingJobIsAlreadyCancelled() = runBlocking {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val released = AtomicInteger(0)
        val child = launch(parent) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                TransferSlotCleanup.afterRejectedStart {
                    yield()
                    released.incrementAndGet()
                }
            }
        }
        started.await()
        parent.cancel()
        child.join()
        assertEquals(1, released.get())
    }

    @Test
    fun immediateResumeCanStartBeforeOldWorkerCleanupReturns() = runBlocking {
        val session = DownloadTransferSession()
        val command = StartTransferCommand("paused-torrent", "/tmp/owned.part")
        assertEquals(SessionCommandResult.StartJob(command), session.handleCommand(1, command))
        var resumed: SessionCommandResult? = null
        TransferSlotCleanup.afterTransferFinished(
            acceptingWork = true,
            finishSession = { session.onTransferFinished(command.downloadId) },
            releaseClaim = { yield() },
            reschedule = { resumed = session.handleCommand(2, command) },
        )
        assertEquals(SessionCommandResult.StartJob(command), resumed)
        assertEquals(null, session.startIdIfIdle())
    }

    private suspend fun runCancelledTransferCleanup(acceptingWork: Boolean): CleanupCounts =
        kotlinx.coroutines.coroutineScope {
        val parent = Job()
        val started = CompletableDeferred<Unit>()
        val released = AtomicInteger(0)
        val rescheduled = AtomicInteger(0)
        val child = launch(parent) {
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                TransferSlotCleanup.afterTransferFinished(
                    acceptingWork = acceptingWork,
                    releaseClaim = {
                        yield()
                        released.incrementAndGet()
                    },
                    reschedule = {
                        yield()
                        rescheduled.incrementAndGet()
                    },
                )
            }
        }
        started.await()
        parent.cancel()
        child.join()
        CleanupCounts(released.get(), rescheduled.get())
    }

    private data class CleanupCounts(val released: Int, val rescheduled: Int)
}
