package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DownloadDetailsPresentationTest {
    private fun record(
        id: String,
        url: String,
        fileName: String,
        destinationPath: String?,
        state: DownloadState,
        totalBytes: Long? = 1_000L,
        downloadedBytes: Long = 0L,
        error: String? = null,
        startedAt: Long? = 1_000L,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = url,
        fileName = fileName,
        destinationPath = destinationPath,
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        startedAtEpochMillis = startedAt,
        completedAtEpochMillis = completedAt,
    )

    @Test
    fun distinctIdsUrlsAndFilesMapIndependently() {
        val dune = mapDownloadToDetailsPresentation(
            record(
                id = "download-dune",
                url = "https://media.example.net/releases/Dune.Part.Two.mkv",
                fileName = "Dune.Part.Two.mkv",
                destinationPath = "/Download/SDM/Dune.Part.Two.mkv",
                state = DownloadState.DOWNLOADING,
                totalBytes = 100L,
                downloadedBytes = 72L,
            ),
            nowEpochMillis = 2_000L,
        )
        val notes = mapDownloadToDetailsPresentation(
            record(
                id = "download-notes",
                url = "https://files.example.org/beta-notes.txt",
                fileName = "beta-notes.txt",
                destinationPath = "/storage/emulated/0/Download/beta-notes.txt",
                state = DownloadState.QUEUED,
                totalBytes = 50L,
                downloadedBytes = 0L,
                startedAt = null,
            ),
            nowEpochMillis = 2_000L,
        )

        assertEquals("download-dune", dune.id)
        assertEquals("https://media.example.net/releases/Dune.Part.Two.mkv", dune.sourceUrl)
        assertEquals("Dune.Part.Two.mkv", dune.fileName)
        assertEquals("/Download/SDM", dune.destinationDisplay)
        assertEquals("72%", dune.percentLabel)
        assertEquals(259.2f, dune.ringSweepDegrees, 0.0001f)
        assertEquals("ACTIVE", dune.stateLabel)
        assertEquals(DownloadDetailsStateTone.Success, dune.stateTone)

        assertEquals("download-notes", notes.id)
        assertEquals("https://files.example.org/beta-notes.txt", notes.sourceUrl)
        assertEquals("beta-notes.txt", notes.fileName)
        assertEquals("/storage/emulated/0/Download", notes.destinationDisplay)
        assertEquals("0%", notes.percentLabel)
        assertEquals(0f, notes.ringSweepDegrees, 0.0001f)
        assertEquals("QUEUED", notes.stateLabel)
        assertEquals(DownloadDetailsStateTone.Muted, notes.stateTone)

        assertNotEquals(dune.id, notes.id)
        assertNotEquals(dune.sourceUrl, notes.sourceUrl)
        assertNotEquals(dune.fileName, notes.fileName)
        assertNotEquals(dune.destinationDisplay, notes.destinationDisplay)
    }

    @Test
    fun unknownTotalDoesNotInventPercentOrRingProgress() {
        val unknown = mapDownloadToDetailsPresentation(
            record(
                id = "download-unknown",
                url = "https://cdn.example.net/stream.bin",
                fileName = "stream.bin",
                destinationPath = "/Download/SDM/stream.bin",
                state = DownloadState.DOWNLOADING,
                totalBytes = null,
                downloadedBytes = 2_048L,
            ),
            nowEpochMillis = 2_000L,
        )

        assertEquals("—", unknown.percentLabel)
        assertEquals(0f, unknown.ringSweepDegrees, 0.0001f)
        assertEquals("ACTIVE", unknown.stateLabel)
        assertEquals("https://cdn.example.net/stream.bin", unknown.sourceUrl)
    }

    @Test
    fun completedShowsFullBoundedProgressAndCompletedLabel() {
        val completed = mapDownloadToDetailsPresentation(
            record(
                id = "download-done",
                url = "https://origin.example.com/archive.zip",
                fileName = "archive.zip",
                destinationPath = "/sdcard/SDM/archive.zip",
                state = DownloadState.COMPLETED,
                totalBytes = 1_000L,
                downloadedBytes = 1_000L,
                completedAt = 2_000L,
            ),
            nowEpochMillis = 3_000L,
        )

        assertEquals("100%", completed.percentLabel)
        assertEquals(360f, completed.ringSweepDegrees, 0.0001f)
        assertEquals("COMPLETED", completed.stateLabel)
        assertEquals(DownloadDetailsStateTone.Success, completed.stateTone)
        assertEquals("/sdcard/SDM", completed.destinationDisplay)
        assertEquals("https://origin.example.com/archive.zip", completed.sourceUrl)
    }

    @Test
    fun destinationPathUsesParentFolderAndUnknownMarkerWhenMissing() {
        val withPathRecord = record(
            id = "with-path",
            url = "https://example.com/a.iso",
            fileName = "a.iso",
            destinationPath = "/Download/SDM/a.iso",
            state = DownloadState.PAUSED,
            downloadedBytes = 250L,
        )
        val missingPathRecord = record(
            id = "no-path",
            url = "https://example.com/b.iso",
            fileName = "b.iso",
            destinationPath = null,
            state = DownloadState.PAUSED,
            downloadedBytes = 250L,
        )
        val withPath = mapDownloadToDetailsPresentation(withPathRecord, nowEpochMillis = 2_000L)
        val missingPath = mapDownloadToDetailsPresentation(missingPathRecord, nowEpochMillis = 2_000L)

        assertEquals("/Download/SDM", withPath.destinationDisplay)
        assertEquals("PAUSED", withPath.stateLabel)
        assertEquals(DownloadDetailsStateTone.Success, withPath.stateTone)
        assertEquals("—", missingPath.destinationDisplay)
        assertEquals("https://example.com/b.iso", missingPath.sourceUrl)

        assertEquals(
            "Opening ${withPath.destinationDisplay}",
            detailsOpenFolderToast(withPathRecord.destinationPath),
        )
        assertEquals("Opening /Download/SDM", detailsOpenFolderToast(withPathRecord.destinationPath))
        assertEquals(
            "Destination folder unavailable",
            detailsOpenFolderToast(missingPathRecord.destinationPath),
        )
        assertNotEquals(
            "Opening ${missingPath.destinationDisplay}",
            detailsOpenFolderToast(missingPathRecord.destinationPath),
        )
        assertEquals(
            "Opening /storage/emulated/0/Download",
            detailsOpenFolderToast("/storage/emulated/0/Download/beta-notes.txt"),
        )
        assertNotEquals(
            "Opening /Download/SDM",
            detailsOpenFolderToast("/storage/emulated/0/Download/beta-notes.txt"),
        )
    }

    @Test
    fun userTreeLabelAndContentUriUseThePersistedFolderName() {
        val labeledRecord = record(
            id = "labeled",
            url = "https://example.com/clip.bin",
            fileName = "clip.bin",
            destinationPath = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/1",
            state = DownloadState.COMPLETED,
            totalBytes = 4L,
            downloadedBytes = 4L,
            completedAt = 2_000L,
        ).copy(destinationDisplayLabel = "Download")
        val labeled = mapDownloadToDetailsPresentation(labeledRecord, nowEpochMillis = 2_000L)
        assertEquals("Download", labeled.destinationDisplay)
        assertEquals("Selected folder", detailsDestinationDisplay(labeledRecord.destinationPath))
        assertEquals("Opening Download", detailsOpenFolderToast(labeledRecord))
    }
}
