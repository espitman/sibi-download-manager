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
