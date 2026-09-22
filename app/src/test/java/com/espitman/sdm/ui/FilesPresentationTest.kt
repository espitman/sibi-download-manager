package com.espitman.sdm.ui

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.CompletedFileProbe
import com.espitman.sdm.storage.FilesystemCompletedFileProbe
import com.espitman.sdm.storage.StorageCapacity
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FilesPresentationTest {
    private lateinit var tempDir: File
    private val zone = ZoneOffset.UTC
    private val now = 1_727_015_520_000L // 2024-09-22 14:32:00 UTC

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("files-presentation").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun onlyCompletedReadableDestinationsAreVisible() {
        val visible = File(tempDir, "movie.mkv").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = File(tempDir, "gone.pdf")
        val directory = File(tempDir, "folder").apply { mkdir() }
        val records = listOf(
            completed("movie", visible.absolutePath, "movie.mkv", mimeType = "video/x-matroska"),
            completed("missing", missing.absolutePath, "gone.pdf", mimeType = "application/pdf"),
            completed("dir", directory.absolutePath, "folder.bin", mimeType = "application/octet-stream"),
            completed("blank", null, "blank.txt", mimeType = "text/plain"),
            record("active", DownloadState.DOWNLOADING, visible.absolutePath, "active.mkv"),
            record("queued", DownloadState.QUEUED, visible.absolutePath, "queued.mkv"),
            record("paused", DownloadState.PAUSED, visible.absolutePath, "paused.mkv"),
            record("failed", DownloadState.FAILED, visible.absolutePath, "failed.mkv", error = "network"),
            record("cancelled", DownloadState.CANCELLED, visible.absolutePath, "cancelled.mkv"),
        )

        val rows = presentCompletedFiles(
            records = records,
            filter = FileTypeFilter.All,
            query = "",
            sort = FileSortOption.NewestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = FilesystemCompletedFileProbe,
        )

        assertEquals(listOf("movie"), rows.map { it.id })
    }

    @Test
    fun unreadableAndContentUriWithoutGrantAreHidden() {
        val local = File(tempDir, "track.flac").apply { writeBytes(byteArrayOf(4, 5)) }
        val records = listOf(
            completed("locked", local.absolutePath, "track.flac", mimeType = "audio/flac"),
            completed(
                id = "saf",
                destinationPath = "content://com.android.externalstorage.documents/document/primary%3Atrack.flac",
                fileName = "track.flac",
                mimeType = "audio/flac",
            ),
        )
        val probe = CompletedFileProbe { false }

        val rows = presentCompletedFiles(
            records = records,
            filter = FileTypeFilter.All,
            query = "",
            sort = FileSortOption.NewestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = probe,
        )

        assertTrue(rows.isEmpty())
        assertFalse(FilesystemCompletedFileProbe.isReadableDocument(records[1].destinationPath!!))
    }

    @Test
    fun contentUriAppearsWhenProbeConfirmsReadableDocument() {
        val uri = "content://com.android.externalstorage.documents/document/primary%3Aguide.pdf"
        val probe = CompletedFileProbe { path -> path == uri }
        val rows = presentCompletedFiles(
            records = listOf(
                completed("doc", uri, "SDM_User_Guide.pdf", mimeType = "application/pdf"),
                completed("other", "content://other/document/missing.bin", "missing.bin"),
            ),
            filter = FileTypeFilter.All,
            query = "",
            sort = FileSortOption.NewestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = probe,
        )
        assertEquals(listOf("doc"), rows.map { it.id })
        assertEquals(FileTypeFilter.Documents, rows.single().category)
        assertEquals(uri, rows.single().identity.destinationPath)
        assertFalse(rows.single().meta.contains("content:"))
    }

    @Test
    fun mimeTypeWinsOverConflictingExtension() {
        assertEquals(FileTypeFilter.Video, classifyCompletedFile("video/mp4", "notes.txt"))
        assertEquals(FileTypeFilter.Audio, classifyCompletedFile("audio/mpeg", "clip.mp4"))
        assertEquals(FileTypeFilter.Apk, classifyCompletedFile("application/vnd.android.package-archive", "setup.zip"))
        assertEquals(FileTypeFilter.Archives, classifyCompletedFile("application/zip", "manual.pdf"))
        assertEquals(FileTypeFilter.Documents, classifyCompletedFile("application/pdf", "movie.mkv"))
    }

    @Test
    fun genericBinaryMimeFallsBackToExtension() {
        assertEquals(FileTypeFilter.Video, classifyCompletedFile("application/octet-stream", "Dune.mkv"))
        assertEquals(FileTypeFilter.Audio, classifyCompletedFile("application/force-download", "score.flac"))
        assertEquals(FileTypeFilter.Apk, classifyCompletedFile(null, "app.apk"))
        assertEquals(FileTypeFilter.Archives, classifyCompletedFile("  APPLICATION/OCTET-STREAM; charset=binary  ", "assets.7z"))
        assertEquals(FileTypeFilter.Documents, classifyCompletedFile(null, "guide.pdf"))
    }

    @Test
    fun unknownBinariesStayAllOnlyAndAreNotDocuments() {
        assertNull(classifyCompletedFile("application/octet-stream", "payload.bin"))
        assertNull(classifyCompletedFile("application/x-msdownload", "setup.exe"))
        assertNull(classifyCompletedFile("image/png", "cover.png"))
        assertNull(classifyCompletedFile("application/x-iso9660-image", "disk.iso"))
        assertNull(classifyCompletedFile(null, "blob.dat"))
        assertNull(classifyCompletedFile("application/octet-stream", "no-extension"))
    }

    @Test
    fun documentLikeUnknownsBelongInDocuments() {
        assertEquals(FileTypeFilter.Documents, classifyCompletedFile("text/plain", "readme"))
        assertEquals(FileTypeFilter.Documents, classifyCompletedFile("application/json", "manifest.bin"))
        assertEquals(FileTypeFilter.Documents, classifyCompletedFile("application/epub+zip", "book.epub"))
        assertEquals(
            FileTypeFilter.Documents,
            classifyCompletedFile(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "notes.bin",
            ),
        )
    }

    @Test
    fun searchIsCaseInsensitiveFilenameMatchAndCombinesWithFilter() {
        val video = File(tempDir, "Dune.Part.Two.mkv").apply { writeBytes(byteArrayOf(1)) }
        val audio = File(tempDir, "Hans_Zimmer.flac").apply { writeBytes(byteArrayOf(2)) }
        val otherVideo = File(tempDir, "other.mp4").apply { writeBytes(byteArrayOf(3)) }
        val records = listOf(
            completed("dune", video.absolutePath, "Dune.Part.Two.mkv", mimeType = "video/x-matroska", completedAt = 3_000L),
            completed("score", audio.absolutePath, "Hans_Zimmer.flac", mimeType = "audio/flac", completedAt = 2_000L),
            completed("other", otherVideo.absolutePath, "other.mp4", mimeType = "video/mp4", completedAt = 1_000L),
        )

        fun ids(filter: FileTypeFilter, query: String) = presentCompletedFiles(
            records = records,
            filter = filter,
            query = query,
            sort = FileSortOption.NewestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = FilesystemCompletedFileProbe,
        ).map { it.id }

        assertEquals(listOf("dune"), ids(FileTypeFilter.All, "dune"))
        assertEquals(listOf("dune"), ids(FileTypeFilter.All, "  DUNE  "))
        assertEquals(listOf("dune"), ids(FileTypeFilter.Video, "part.two"))
        assertTrue(ids(FileTypeFilter.Audio, "dune").isEmpty())
        assertTrue(ids(FileTypeFilter.All, "no-such-file").isEmpty())
        assertEquals(listOf("dune", "other"), ids(FileTypeFilter.Video, ""))
        assertEquals(listOf("dune", "score", "other"), ids(FileTypeFilter.All, "   "))
    }

    @Test
    fun newestFirstIsDefaultAndOldestReversesWithStableIdTieBreak() {
        val first = File(tempDir, "a.mkv").apply { writeBytes(byteArrayOf(1)) }
        val second = File(tempDir, "b.mkv").apply { writeBytes(byteArrayOf(2)) }
        val third = File(tempDir, "c.mkv").apply { writeBytes(byteArrayOf(3)) }
        val records = listOf(
            completed("b-tie", second.absolutePath, "b.mkv", mimeType = "video/mp4", completedAt = 5_000L),
            completed("a-tie", first.absolutePath, "a.mkv", mimeType = "video/mp4", completedAt = 5_000L),
            completed("older", third.absolutePath, "c.mkv", mimeType = "video/mp4", completedAt = 4_000L),
        )

        val newest = presentCompletedFiles(
            records = records,
            filter = FileTypeFilter.All,
            query = "",
            sort = FileSortOption.NewestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = FilesystemCompletedFileProbe,
        ).map { it.id }
        val oldest = presentCompletedFiles(
            records = records,
            filter = FileTypeFilter.All,
            query = "",
            sort = FileSortOption.OldestFirst,
            nowEpochMillis = now,
            zoneId = zone,
            probe = FilesystemCompletedFileProbe,
        ).map { it.id }

        assertEquals(listOf("a-tie", "b-tie", "older"), newest)
        assertEquals(listOf("older", "a-tie", "b-tie"), oldest)
        assertEquals("Sorted newest first", FileSortOption.NewestFirst.toast)
        assertEquals("Sorted oldest first", FileSortOption.OldestFirst.toast)
    }

    @Test
    fun metadataUsesStoredBytesAndCompletedTimestampWithoutInventingVerified() {
        val file = File(tempDir, "SDM.Premium.v4.8.2.apk").apply { writeBytes(ByteArray(186)) }
        val row = mapCompletedFile(
            download = completed(
                id = "apk",
                destinationPath = file.absolutePath,
                fileName = "SDM.Premium.v4.8.2.apk",
                mimeType = "application/vnd.android.package-archive",
                downloadedBytes = 186L * 1024L * 1024L,
                completedAt = now,
                referenceSha256 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            ),
            nowEpochMillis = now,
            zoneId = zone,
            probe = FilesystemCompletedFileProbe,
        )!!

        assertEquals("186.00 MB · Today, 14:32", row.meta)
        assertFalse(row.verified)
        assertEquals("✓ Complete", if (row.verified) "✓ Verified" else "✓ Complete")
        assertEquals(FileTypeFilter.Apk, row.category)
        assertEquals("APK", row.type)
        assertEquals("apk", row.identity.downloadId)
        assertEquals(file.absolutePath, row.identity.destinationPath)
        assertEquals("application/vnd.android.package-archive", row.identity.persistedMimeType)
        assertEquals("SDM.Premium.v4.8.2.apk", row.identity.fileName)
        assertFalse(row.meta.contains(file.absolutePath))
        assertFalse(row.name.contains("/"))
    }

    @Test
    fun yesterdayAndCalendarDatesMatchDesign() {
        val yesterday = formatCompletedTimestamp(now - 86_400_000L, now, zone)
        val earlier = formatCompletedTimestamp(1_726_611_600_000L, now, zone) // 2024-09-17 22:20 UTC
        assertEquals("Yesterday, 14:32", yesterday)
        assertEquals("Sep 17, 22:20", earlier)
    }

    @Test
    fun verifiedOnlyWhenCallerSuppliesSuccessfulChecksumEvidence() {
        val file = File(tempDir, "checked.pdf").apply { writeBytes(byteArrayOf(9)) }
        val download = completed(
            id = "checked",
            destinationPath = file.absolutePath,
            fileName = "checked.pdf",
            mimeType = "application/pdf",
            referenceSha256 = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        )
        val unverified = mapCompletedFile(download, now, zone, FilesystemCompletedFileProbe)!!
        val verified = mapCompletedFile(
            download,
            now,
            zone,
            FilesystemCompletedFileProbe,
            verifiedIds = setOf("checked"),
        )!!
        assertFalse(unverified.verified)
        assertTrue(verified.verified)
    }

    @Test
    fun storageCardKeepsEmDashesWhenCapacityIsUnknown() {
        val figures = storageCardFigures(StorageCapacity.Unknown)
        assertEquals("—", figures.usedLabel)
        assertEquals("—", figures.ofTotalLabel)
        assertEquals("—", figures.availableLabel)
        assertEquals("—", figures.usedPercentLabel)
        assertEquals(0f, figures.usedFraction)
    }

    @Test
    fun storageCardShowsKnownUsedTotalAvailableAndPercent() {
        val figures = storageCardFigures(StorageCapacity.from(totalBytes = 100L, availableBytes = 36L))
        assertEquals("64 B", figures.usedLabel)
        assertEquals("OF 100 B", figures.ofTotalLabel)
        assertEquals("36 B available", figures.availableLabel)
        assertEquals("64% used", figures.usedPercentLabel)
        assertEquals(64f / 100f, figures.usedFraction)
    }

    @Test
    fun storageCardDoesNotInventUsedWhenOnlyAvailableIsKnown() {
        val figures = storageCardFigures(StorageCapacity.from(totalBytes = null, availableBytes = 40L))
        assertEquals("—", figures.usedLabel)
        assertEquals("—", figures.ofTotalLabel)
        assertEquals("40 B available", figures.availableLabel)
        assertEquals("—", figures.usedPercentLabel)
        assertEquals(0f, figures.usedFraction)
    }

    @Test
    fun storageReloadKeyChangesWhenCompletedFilesChange() {
        val first = File(tempDir, "one.bin").apply { writeBytes(byteArrayOf(1, 2)) }
        val second = File(tempDir, "two.bin").apply { writeBytes(byteArrayOf(3)) }
        val one = mapCompletedFile(
            completed("one", first.absolutePath, "one.bin", downloadedBytes = 2L),
            now,
            zone,
            FilesystemCompletedFileProbe,
        )!!
        val two = mapCompletedFile(
            completed("two", second.absolutePath, "two.bin", downloadedBytes = 1L),
            now,
            zone,
            FilesystemCompletedFileProbe,
        )!!
        val before = filesStorageReloadKey(listOf(one))
        val afterComplete = filesStorageReloadKey(listOf(one, two))
        val afterDelete = filesStorageReloadKey(emptyList())
        val renamed = one.copy(
            name = "renamed.bin",
            identity = one.identity.copy(destinationPath = File(tempDir, "renamed.bin").absolutePath),
        )
        assertEquals(listOf(Triple("one", 2L, first.absolutePath)), before)
        assertEquals(2, afterComplete.size)
        assertTrue(afterComplete != before)
        assertTrue(afterDelete != before)
        assertTrue(filesStorageReloadKey(listOf(renamed)) != before)
        assertEquals(before, filesStorageReloadKey(listOf(one.copy(meta = "changed label"))))
    }

    private fun completed(
        id: String,
        destinationPath: String?,
        fileName: String,
        mimeType: String? = null,
        downloadedBytes: Long = 3L,
        completedAt: Long = 2_000L,
        referenceSha256: String? = null,
    ) = record(
        id = id,
        state = DownloadState.COMPLETED,
        destinationPath = destinationPath,
        fileName = fileName,
        mimeType = mimeType,
        downloadedBytes = downloadedBytes,
        completedAt = completedAt,
        referenceSha256 = referenceSha256,
    )

    private fun record(
        id: String,
        state: DownloadState,
        destinationPath: String?,
        fileName: String,
        mimeType: String? = null,
        downloadedBytes: Long = 3L,
        completedAt: Long? = null,
        error: String? = null,
        referenceSha256: String? = null,
    ) = Download(
        id = id,
        url = "https://example.com/$id",
        fileName = fileName,
        mimeType = mimeType,
        destinationPath = destinationPath,
        totalBytes = if (state == DownloadState.COMPLETED) downloadedBytes else 1_000L,
        downloadedBytes = if (state == DownloadState.COMPLETED) downloadedBytes else 0L,
        state = state,
        error = error,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 2_000L,
        completedAtEpochMillis = completedAt ?: if (state == DownloadState.COMPLETED) 2_000L else null,
        referenceSha256 = referenceSha256,
    )
}
