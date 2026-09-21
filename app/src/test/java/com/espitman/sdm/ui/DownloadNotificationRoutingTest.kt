package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DownloadNotificationRoutingTest {
    private fun record(id: String) = Download(
        id = id,
        url = "https://example.com/$id.zip",
        fileName = "$id.zip",
        destinationPath = "/downloads/$id.zip",
        totalBytes = 1_000L,
        downloadedBytes = 0L,
        state = DownloadState.QUEUED,
        error = null,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        startedAtEpochMillis = null,
        completedAtEpochMillis = null,
    )

    @Test
    fun resolveSelectedDownloadReturnsExactId() {
        val matching = record("download-a")
        val records = listOf(record("download-b"), matching, record("download-c"))

        assertSame(matching, resolveSelectedDownload(records, "download-a"))
        assertEquals("download-a", resolveSelectedDownload(records, "download-a")?.id)
    }

    @Test
    fun resolveSelectedDownloadReturnsNullForNullId() {
        assertNull(resolveSelectedDownload(listOf(record("download-a")), null))
        assertNull(resolveSelectedDownload(emptyList(), null))
    }

    @Test
    fun resolveSelectedDownloadReturnsNullForMissingId() {
        val records = listOf(record("download-a"), record("download-b"))

        assertNull(resolveSelectedDownload(records, "download-missing"))
        assertNull(resolveSelectedDownload(emptyList(), "download-a"))
    }
}
