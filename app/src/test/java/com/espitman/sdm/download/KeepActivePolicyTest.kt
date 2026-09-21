package com.espitman.sdm.download

import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeepActivePolicyTest {
    @Test
    fun keepActiveFalseNeverHolds() {
        DownloadState.entries.forEach { state ->
            assertFalse(
                KeepActivePolicy.shouldHoldWakeLock(
                    keepActive = false,
                    keepActiveDuration = KeepActivePolicy.DURATION_DOWNLOADING,
                    states = listOf(state),
                ),
            )
            assertFalse(
                KeepActivePolicy.shouldHoldWakeLock(
                    keepActive = false,
                    keepActiveDuration = KeepActivePolicy.DURATION_QUEUE,
                    states = listOf(state),
                ),
            )
        }
    }

    @Test
    fun downloadingDurationHoldsOnlyWhileConnectingOrDownloading() {
        assertTrue(holdDownloading(DownloadState.CONNECTING))
        assertTrue(holdDownloading(DownloadState.DOWNLOADING))
        assertFalse(holdDownloading(DownloadState.QUEUED))
        assertFalse(holdDownloading(DownloadState.PAUSED))
        assertFalse(holdDownloading(DownloadState.COMPLETED))
        assertFalse(holdDownloading(DownloadState.FAILED))
        assertFalse(holdDownloading(DownloadState.CANCELLED))
        assertFalse(
            KeepActivePolicy.shouldHoldWakeLock(
                keepActive = true,
                keepActiveDuration = KeepActivePolicy.DURATION_DOWNLOADING,
                states = emptyList(),
            ),
        )
        assertTrue(
            KeepActivePolicy.shouldHoldWakeLock(
                keepActive = true,
                keepActiveDuration = KeepActivePolicy.DURATION_DOWNLOADING,
                states = listOf(DownloadState.QUEUED, DownloadState.DOWNLOADING, DownloadState.PAUSED),
            ),
        )
    }

    @Test
    fun queueDurationHoldsQueuedConnectingOrDownloading() {
        assertTrue(holdQueue(DownloadState.QUEUED))
        assertTrue(holdQueue(DownloadState.CONNECTING))
        assertTrue(holdQueue(DownloadState.DOWNLOADING))
        assertFalse(holdQueue(DownloadState.PAUSED))
        assertFalse(holdQueue(DownloadState.COMPLETED))
        assertFalse(holdQueue(DownloadState.FAILED))
        assertFalse(holdQueue(DownloadState.CANCELLED))
        assertFalse(
            KeepActivePolicy.shouldHoldWakeLock(
                keepActive = true,
                keepActiveDuration = KeepActivePolicy.DURATION_QUEUE,
                states = emptyList(),
            ),
        )
    }

    @Test
    fun unknownDurationNeverHolds() {
        assertFalse(
            KeepActivePolicy.shouldHoldWakeLock(
                keepActive = true,
                keepActiveDuration = "always",
                states = listOf(DownloadState.DOWNLOADING, DownloadState.QUEUED),
            ),
        )
    }

    private fun holdDownloading(state: DownloadState) = KeepActivePolicy.shouldHoldWakeLock(
        keepActive = true,
        keepActiveDuration = KeepActivePolicy.DURATION_DOWNLOADING,
        states = listOf(state),
    )

    private fun holdQueue(state: DownloadState) = KeepActivePolicy.shouldHoldWakeLock(
        keepActive = true,
        keepActiveDuration = KeepActivePolicy.DURATION_QUEUE,
        states = listOf(state),
    )
}
