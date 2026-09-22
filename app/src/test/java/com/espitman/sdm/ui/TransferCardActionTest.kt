package com.espitman.sdm.ui

import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Test

class TransferCardActionTest {
    @Test
    fun pausedResumesActivePausesAndFailedRetries() {
        assertEquals(TransferCardAction.Resume, transferCardAction(DownloadState.PAUSED))
        assertEquals(TransferCardAction.Pause, transferCardAction(DownloadState.CONNECTING))
        assertEquals(TransferCardAction.Pause, transferCardAction(DownloadState.DOWNLOADING))
        assertEquals(TransferCardAction.Retry, transferCardAction(DownloadState.FAILED))
        assertEquals(TransferCardAction.Start, transferCardAction(DownloadState.QUEUED))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.COMPLETED))
        assertEquals(TransferCardAction.None, transferCardAction(DownloadState.CANCELLED))
    }

    @Test
    fun detailsPrimaryActionIsRetryResumeOrPause() {
        assertEquals(TransferCardAction.Retry, detailsPrimaryAction(DownloadState.FAILED))
        assertEquals("Retry", detailsPrimaryActionLabel(detailsPrimaryAction(DownloadState.FAILED)))
        assertEquals(TransferCardAction.Resume, detailsPrimaryAction(DownloadState.PAUSED))
        assertEquals("Resume", detailsPrimaryActionLabel(detailsPrimaryAction(DownloadState.PAUSED)))
        assertEquals(TransferCardAction.Pause, detailsPrimaryAction(DownloadState.CONNECTING))
        assertEquals("Pause", detailsPrimaryActionLabel(detailsPrimaryAction(DownloadState.CONNECTING)))
        assertEquals(TransferCardAction.Pause, detailsPrimaryAction(DownloadState.DOWNLOADING))
        assertEquals("Pause", detailsPrimaryActionLabel(detailsPrimaryAction(DownloadState.DOWNLOADING)))
        assertEquals("Start", detailsPrimaryActionLabel(detailsPrimaryAction(DownloadState.QUEUED)))
        assertEquals(TransferCardAction.None, detailsPrimaryAction(DownloadState.COMPLETED))
        assertEquals(TransferCardAction.None, detailsPrimaryAction(DownloadState.CANCELLED))
    }

    @Test
    fun retryAndResumeDispatchResumeTransferWhileActivePauses() {
        fun capture(state: DownloadState): String {
            var dispatched = "none"
            dispatchTransferCardAction(
                action = transferCardAction(state),
                start = { dispatched = "start" },
                pause = { dispatched = "pause" },
                resumeOrRetry = { dispatched = "resume" },
            )
            return dispatched
        }

        assertEquals("resume", capture(DownloadState.FAILED))
        assertEquals("resume", capture(DownloadState.PAUSED))
        assertEquals("pause", capture(DownloadState.CONNECTING))
        assertEquals("pause", capture(DownloadState.DOWNLOADING))
        assertEquals("start", capture(DownloadState.QUEUED))
        assertEquals("none", capture(DownloadState.COMPLETED))
        assertEquals("none", capture(DownloadState.CANCELLED))
    }

    @Test
    fun priorityToastsMatchTheApprovedDetailsCopy() {
        assertEquals("High priority enabled", priorityToggleToast(true))
        assertEquals("Priority returned to normal", priorityToggleToast(false))
    }
}
