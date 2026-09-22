package com.espitman.sdm.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadRenameMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.CompletedFileDeleteCoordinator
import com.espitman.sdm.download.CompletedFileDeleteResult
import com.espitman.sdm.download.DownloadRenameCoordinator
import com.espitman.sdm.download.DownloadRenameResult
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletedFileDocumentsContractTest {
    private lateinit var targetContext: Context
    private lateinit var store: DocumentsContractContentDocuments

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        SdmTestDocumentsProvider.reset(targetContext)
        store = DocumentsContractContentDocuments(
            contentResolver = targetContext.contentResolver,
            hasTreeWriteGrant = { it == SdmTestDocumentsProvider.treeUri().toString() },
        )
    }

    @After
    fun tearDown() {
        SdmTestDocumentsProvider.reset(targetContext)
    }

    @Test
    fun treeUriIdentifiesRootDocumentIdNotRootsTableId() {
        val tree = SdmTestDocumentsProvider.treeUri()
        assertEquals(SdmTestDocumentsProvider.AUTHORITY, tree.authority)
        assertEquals(SdmTestDocumentsProvider.ROOT_DOC_ID, DocumentsContract.getTreeDocumentId(tree))
        assertNotEquals(SdmTestDocumentsProvider.ROOT_ID, DocumentsContract.getTreeDocumentId(tree))

        val parent = SdmTestDocumentsProvider.parentUri()
        assertEquals(SdmTestDocumentsProvider.ROOT_DOC_ID, DocumentsContract.getDocumentId(parent))
        assertEquals(SdmTestDocumentsProvider.ROOT_DOC_ID, DocumentsContract.getTreeDocumentId(parent))

        val child = SdmTestDocumentsProvider.documentUri("doc-child")
        assertEquals("doc-child", DocumentsContract.getDocumentId(child))
        assertEquals(SdmTestDocumentsProvider.ROOT_DOC_ID, DocumentsContract.getTreeDocumentId(child))
    }

    @Test
    fun documentsContractRenamesAndDeletesACreatedDocument() {
        val created = createDocument("sdm-043.txt", "hello")
        val renamed = DocumentsContract.renameDocument(
            targetContext.contentResolver,
            created,
            "sdm-043-renamed.txt",
        )
        assertNotNull(renamed)
        assertNotEquals(created.toString(), renamed.toString())
        assertEquals("sdm-043-renamed.txt", queryDisplayName(renamed!!))
        assertEquals("hello", readDocument(renamed))
        assertTrue(DocumentsContract.deleteDocument(targetContext.contentResolver, renamed))
        assertFalse(documentExists(renamed))
    }

    @Test
    fun coordinatorRenamesSafDocumentAndUpdatesRecord() = runBlocking {
        val created = createDocument("original.bin", "payload")
        val repository = FakeRepository(
            completed(
                id = "saf",
                fileName = "original.bin",
                destination = created.toString(),
            ),
        )

        val result = DownloadRenameCoordinator.rename(
            downloadId = "saf",
            rawFilename = "renamed.bin",
            repository = repository,
            clock = FixedClock(9_000L),
            contentDocuments = store,
        ) as DownloadRenameResult.Success

        assertEquals("renamed.bin", result.download.fileName)
        assertEquals("renamed.bin", repository.get("saf")!!.fileName)
        assertNotEquals(created.toString(), result.download.destinationPath)
        assertEquals("payload", readDocument(Uri.parse(result.download.destinationPath)))
        assertFalse(documentExists(created))
    }

    @Test
    fun coordinatorDeletesSafDocumentAndRecord() = runBlocking {
        val created = createDocument("remove.bin", "x")
        val repository = FakeRepository(
            completed(
                id = "remove",
                fileName = "remove.bin",
                destination = created.toString(),
            ),
        )

        val result = CompletedFileDeleteCoordinator.delete("remove", repository, store)

        assertTrue(result is CompletedFileDeleteResult.Deleted)
        assertFalse(documentExists(created))
        assertNull(repository.get("remove"))
    }

    @Test
    fun revokedGrantDoesNotDeleteDocumentOrRecord() = runBlocking {
        val created = createDocument("locked.bin", "secret")
        val revoked = DocumentsContractContentDocuments(
            contentResolver = targetContext.contentResolver,
            hasTreeWriteGrant = { false },
        )
        val repository = FakeRepository(
            completed(
                id = "locked",
                fileName = "locked.bin",
                destination = created.toString(),
            ),
        )

        val result = CompletedFileDeleteCoordinator.delete("locked", repository, revoked)

        assertEquals(CompletedFileUserMessages.ACCESS_UNAVAILABLE, result.message)
        assertTrue(documentExists(created))
        assertEquals("locked.bin", repository.get("locked")!!.fileName)
    }

    @Test
    fun localFileRenameAndDeleteUseRealFilesystem() = runBlocking {
        val directory = AppSpecificDownloadsDirectory.from(targetContext)
        val source = File(directory, "sdm-043-local.bin").apply { writeText("local-bytes") }
        val repository = FakeRepository(
            completed(
                id = "local",
                fileName = "sdm-043-local.bin",
                destination = source.absolutePath,
                treeUri = null,
            ),
        )

        val renamed = DownloadRenameCoordinator.rename(
            downloadId = "local",
            rawFilename = "sdm-043-local-new.bin",
            repository = repository,
            clock = FixedClock(5_000L),
        ) as DownloadRenameResult.Success
        val target = File(directory, "sdm-043-local-new.bin")
        assertEquals(target.absolutePath, renamed.download.destinationPath)
        assertEquals("local-bytes", target.readText())
        assertFalse(source.exists())

        val deleted = CompletedFileDeleteCoordinator.delete("local", repository)
        assertTrue(deleted is CompletedFileDeleteResult.Deleted)
        assertFalse(target.exists())
        assertNull(repository.get("local"))
    }

    private fun createDocument(name: String, contents: String): Uri {
        val created = DocumentsContract.createDocument(
            targetContext.contentResolver,
            SdmTestDocumentsProvider.parentUri(),
            "text/plain",
            name,
        )
        assertNotNull(created)
        targetContext.contentResolver.openOutputStream(created!!)!!.use { it.write(contents.toByteArray()) }
        return created
    }

    private fun readDocument(uri: Uri): String =
        targetContext.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun queryDisplayName(uri: Uri): String? {
        targetContext.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null || !cursor.moveToFirst()) return null
            return cursor.getString(0)
        }
    }

    private fun documentExists(uri: Uri): Boolean = try {
        targetContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    private fun completed(
        id: String,
        fileName: String,
        destination: String,
        treeUri: String? = SdmTestDocumentsProvider.treeUri().toString(),
    ) = Download(
        id = id,
        url = "https://example.com/$fileName",
        fileName = fileName,
        destinationPath = destination,
        destinationTreeUri = treeUri,
        destinationDisplayLabel = if (treeUri == null) null else "Test",
        totalBytes = 4,
        downloadedBytes = 4,
        state = DownloadState.COMPLETED,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

    private class FixedClock(private val time: Long) : Clock {
        override fun currentTimeMillis(): Long = time
    }

    private class FakeRepository(initial: Download) : DownloadRepository {
        private val _downloads = MutableStateFlow(listOf(initial))
        override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
        override suspend fun awaitInitialized() {}
        override suspend fun get(id: String): Download? = _downloads.value.find { it.id == id }
        override suspend fun insert(download: Download) {
            _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
        }
        override suspend fun delete(id: String): Boolean {
            val existed = _downloads.value.any { it.id == id }
            _downloads.value = _downloads.value.filterNot { it.id == id }
            return existed
        }
        override suspend fun transition(id: String, to: DownloadState, nowEpochMillis: Long, error: String?) =
            error("unused")
        override suspend fun updateProgress(id: String, downloadedBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun pauseAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun cancelAtExactOffset(id: String, fileLengthBytes: Long, nowEpochMillis: Long) = error("unused")
        override suspend fun resumePaused(id: String, nowEpochMillis: Long) = error("unused")
        override suspend fun togglePriority(id: String, nowEpochMillis: Long) = error("unused")
        override suspend fun beginFreshRestart(
            id: String,
            nowEpochMillis: Long,
            etag: String?,
            lastModified: String?,
            totalBytes: Long?,
        ) = error("unused")
        override suspend fun renameRecord(
            id: String,
            fileName: String,
            destinationPath: String,
            nowEpochMillis: Long,
        ): Download {
            val current = get(id) ?: error("missing")
            val updated = DownloadRenameMutation.apply(current, fileName, destinationPath, nowEpochMillis)
            insert(updated)
            return updated
        }
    }
}
