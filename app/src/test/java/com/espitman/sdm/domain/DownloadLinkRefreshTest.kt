package com.espitman.sdm.domain

import com.espitman.sdm.network.DownloadMetadata
import org.junit.Assert.*
import org.junit.Test

class DownloadLinkRefreshTest {
    private val original = Download(url = "http://example.com/old?expired=1", fileName = "file.bin",
        destinationPath = "/tmp/file.bin", downloadedBytes = 40, totalBytes = 100, etag = "\"v1\"",
        state = DownloadState.FAILED, error = "Expired link", sortOrder = 42, priority = 1, createdAtEpochMillis = 1)
    private val metadata = DownloadMetadata(url = "http://example.com/new?token=2", contentLength = 100,
        acceptsRanges = true, etag = "\"v1\"", suggestedFilename = "file.bin")
    @Test fun preservesProgressDestinationAndQueueOrderWithStrongIdentity() {
        val replaced = DownloadLinkRefresh.replacement(original, metadata, false, 2)
        assertEquals(40L, replaced.downloadedBytes); assertEquals(42L, replaced.sortOrder)
        assertEquals(original.destinationPath, replaced.destinationPath); assertEquals(1, replaced.priority)
        assertEquals(metadata.url, replaced.url); assertNull(replaced.error); assertEquals(DownloadState.PAUSED, replaced.state)
    }
    @Test fun rejectsChangedWeakUnknownAndNonResumableContent() {
        assertFalse(DownloadLinkRefresh.canPreserve(original, metadata.copy(etag = "\"v2\"")))
        assertFalse(DownloadLinkRefresh.canPreserve(original.copy(etag = "W/\"v1\""), metadata.copy(etag = "W/\"v1\"")))
        assertFalse(DownloadLinkRefresh.canPreserve(original.copy(etag = null), metadata.copy(etag = null)))
        assertFalse(DownloadLinkRefresh.canPreserve(original, metadata.copy(acceptsRanges = false)))
        assertFalse(DownloadLinkRefresh.canPreserve(original, metadata.copy(contentLength = 101)))
    }
    @Test fun explicitRestartResetsBytesButNotQueueOrDestination() {
        val replaced = DownloadLinkRefresh.replacement(original, metadata.copy(etag = "\"v2\""), true, 2)
        assertEquals(0L, replaced.downloadedBytes); assertEquals(original.destinationPath, replaced.destinationPath)
        assertEquals(original.sortOrder, replaced.sortOrder)
    }
    @Test fun zeroBytesAndMatchingChecksumAreSafe() {
        assertTrue(DownloadLinkRefresh.canPreserve(original.copy(downloadedBytes = 0), metadata.copy(acceptsRanges = false)))
        val hash = "a".repeat(64)
        assertTrue(DownloadLinkRefresh.canPreserve(original.copy(referenceSha256 = hash), metadata.copy(etag = null, referenceSha256 = hash)))
        assertFalse(DownloadLinkRefresh.canPreserve(original.copy(referenceSha256 = hash), metadata.copy(referenceSha256 = "b".repeat(64))))
    }
    @Test(expected = IllegalArgumentException::class) fun activeTransferCannotBeReplaced() {
        DownloadLinkRefresh.replacement(original.copy(state = DownloadState.DOWNLOADING, error = null), metadata, true, 2)
    }
    @Test(expected = IllegalArgumentException::class) fun unsafeResumeRequiresConfirmation() {
        DownloadLinkRefresh.replacement(original, metadata.copy(etag = null), false, 2)
    }
}
