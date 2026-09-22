package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadStateMachineTest {
    private fun queued() = Download(
        id = "download-1",
        url = "https://example.com/file.zip",
        fileName = "file.zip",
        totalBytes = 100,
        createdAtEpochMillis = 1_000,
    )

    @Test
    fun validLifecycleTracksTimestamps() {
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
        val downloading = DownloadStateMachine.transition(connecting, DownloadState.DOWNLOADING, 3_000)
        val completed = DownloadStateMachine.transition(
            downloading.copy(downloadedBytes = 100),
            DownloadState.COMPLETED,
            4_000,
        )

        assertEquals(2_000L, completed.startedAtEpochMillis)
        assertEquals(4_000L, completed.completedAtEpochMillis)
        assertEquals(DownloadState.COMPLETED, completed.state)
    }

    @Test
    fun terminalStatesRejectFurtherOperations() {
        DownloadState.entries.forEach { target ->
            assertFalse(DownloadStateMachine.canTransition(DownloadState.COMPLETED, target))
        }
        assertTrue(DownloadStateMachine.canTransition(DownloadState.CANCELLED, DownloadState.QUEUED))
    }

    @Test
    fun pauseAndRetryPathsAreExplicit() {
        assertTrue(DownloadStateMachine.canTransition(DownloadState.DOWNLOADING, DownloadState.PAUSED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.QUEUED))
        assertTrue(DownloadStateMachine.canTransition(DownloadState.FAILED, DownloadState.QUEUED))
        assertFalse(DownloadStateMachine.canTransition(DownloadState.PAUSED, DownloadState.DOWNLOADING))
    }

    @Test
    fun invalidTransitionAndFailureWithoutErrorAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(queued(), DownloadState.COMPLETED, 2_000)
        }
        val connecting = DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000)
        assertThrows(IllegalArgumentException::class.java) {
            DownloadStateMachine.transition(connecting, DownloadState.FAILED, 3_000)
        }
    }

    @Test
    fun invalidModelProgressIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(downloadedBytes = 101)
        }
    }

    @Test
    fun unsafeFilenameAndUnsupportedUrlAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(fileName = "../file.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(url = "file:///private/file.zip")
        }
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(destinationTreeUri = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(destinationDisplayLabel = " ")
        }
        assertThrows(IllegalArgumentException::class.java) {
            queued().copy(pauseCause = DownloadPauseCause.NETWORK_POLICY)
        }
    }

    @Test
    fun leavingPausedClearsPauseCause() {
        val paused = DownloadStateMachine.transition(
            DownloadStateMachine.transition(queued(), DownloadState.CONNECTING, 2_000),
            DownloadState.PAUSED,
            3_000,
        ).copy(pauseCause = DownloadPauseCause.NETWORK_POLICY)
        val queuedAgain = DownloadStateMachine.transition(paused, DownloadState.QUEUED, 4_000)
        assertEquals(null, queuedAgain.pauseCause)
        assertEquals(DownloadState.QUEUED, queuedAgain.state)
    }
}
