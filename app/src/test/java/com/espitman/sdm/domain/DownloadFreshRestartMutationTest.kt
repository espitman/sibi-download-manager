package com.espitman.sdm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class DownloadFreshRestartMutationTest {
    @Test
    fun connectingRecordResetsOffsetAndStoresNewValidators() {
        val connecting = DownloadStateMachine.transition(
            Download(
                id = "download-1",
                url = "https://example.com/file.zip",
                fileName = "file.zip",
                etag = "\"old\"",
                lastModified = "Wed, 21 Oct 2015 07:28:00 GMT",
                totalBytes = 8_000,
                downloadedBytes = 1_024,
                createdAtEpochMillis = 1_000,
            ),
            DownloadState.CONNECTING,
            2_000,
        )

        val restarted = DownloadFreshRestartMutation.apply(
            current = connecting,
            nowEpochMillis = 1_500L,
            etag = " \"new\" ",
            lastModified = " Thu, 22 Oct 2015 07:28:00 GMT ",
            totalBytes = 4_000L,
        )

        assertEquals(DownloadState.CONNECTING, restarted.state)
        assertEquals(0L, restarted.downloadedBytes)
        assertEquals("\"new\"", restarted.etag)
        assertEquals("Thu, 22 Oct 2015 07:28:00 GMT", restarted.lastModified)
        assertEquals(4_000L, restarted.totalBytes)
        assertEquals(2_000L, restarted.updatedAtEpochMillis)
        assertNull(restarted.error)
    }

    @Test
    fun rejectsNonConnectingStates() {
        val queued = Download(
            id = "download-1",
            url = "https://example.com/file.zip",
            fileName = "file.zip",
            downloadedBytes = 10,
            createdAtEpochMillis = 1_000,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DownloadFreshRestartMutation.apply(queued, 2_000L, "\"n\"", null, 8L)
        }
    }
}
