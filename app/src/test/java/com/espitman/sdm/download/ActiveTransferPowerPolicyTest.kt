package com.espitman.sdm.download

import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTransferPowerPolicyTest {
    @Test fun activeDownloadKeepsCpuProtectedWithoutAnOptionalSetting() {
        assertTrue(ActiveTransferPowerPolicy.shouldHoldWakeLock(listOf(DownloadState.CONNECTING)))
        assertTrue(ActiveTransferPowerPolicy.shouldHoldWakeLock(listOf(DownloadState.DOWNLOADING)))
    }
    @Test fun lastTransferPauseOrCompletionReleasesProtection() {
        assertFalse(ActiveTransferPowerPolicy.shouldHoldWakeLock(listOf(DownloadState.PAUSED, DownloadState.QUEUED)))
        assertFalse(ActiveTransferPowerPolicy.shouldHoldWakeLock(listOf(DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED)))
        assertFalse(ActiveTransferPowerPolicy.shouldHoldWakeLock(emptyList()))
    }
    @Test fun pausingOneTransferDoesNotReleaseAnotherTransfersProtection() {
        assertTrue(ActiveTransferPowerPolicy.shouldHoldWakeLock(listOf(DownloadState.PAUSED, DownloadState.DOWNLOADING)))
    }
}
