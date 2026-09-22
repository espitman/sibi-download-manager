package com.espitman.sdm.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.download.Clock
import com.espitman.sdm.download.CompletedFileDeleteCoordinator
import com.espitman.sdm.download.CompletedFileDeleteResult
import com.espitman.sdm.download.DownloadRenameCoordinator
import com.espitman.sdm.download.DownloadRenameResult
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device SAF regression for folder selection, collisions, open/share,
 * rename/delete, and lost or revoked tree access.
 *
 * Uses [SdmTestDocumentsProvider] with isolated preferences, database, grant
 * fakes, and a cache-scoped download directory. Does not touch `sdm_settings`
 * or the real app Downloads folder, and does not launch VIEW/SEND activities.
 */
@RunWith(AndroidJUnit4::class)
class StorageAccessRegressionTest {
    private lateinit var context: Context
    private lateinit var preferences: android.content.SharedPreferences
    private lateinit var downloadsDir: File
    private lateinit var grants: IsolatedTreeUriGrantStore
    private lateinit var trees: DocumentsContractTreeAccess
    private lateinit var store: SaveLocationStore
    private lateinit var coordinator: SaveLocationCoordinator
    private lateinit var settingsSnapshot: Map<String, Any?>
    private var repository: SqliteDownloadRepository? = null
    private var databaseName: String? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        settingsSnapshot = context.getSharedPreferences(REAL_SETTINGS, Context.MODE_PRIVATE).all.toMap()
        SdmTestDocumentsProvider.reset(context)
        preferences = context.getSharedPreferences(TEST_PREFS, Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        downloadsDir = File(context.cacheDir, TEST_DOWNLOADS)
        downloadsDir.deleteRecursively()
        downloadsDir.mkdirs()
        grants = IsolatedTreeUriGrantStore()
        trees = DocumentsContractTreeAccess(context.contentResolver)
        store = SaveLocationStore(preferences)
        coordinator = SaveLocationCoordinator(store, grants, trees)
    }

    @After
    fun tearDown() {
        repository?.close()
        databaseName?.let { context.deleteDatabase(it) }
        SdmTestDocumentsProvider.reset(context)
        preferences.edit().clear().commit()
        downloadsDir.deleteRecursively()
        assertEquals(
            settingsSnapshot,
            context.getSharedPreferences(REAL_SETTINGS, Context.MODE_PRIVATE).all.toMap(),
        )
    }

    @Test
    fun openDocumentTreeContractPersistsSelectionThroughStoreRecreation() {
        val picker = OpenDocumentTreeAccess.createPickerIntent()
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, picker.action)
        assertEquals(StorageAccessPolicy.PICKER_INTENT_FLAGS, picker.flags and StorageAccessPolicy.PICKER_INTENT_FLAGS)
        assertFalse(picker.hasExtra(DocumentsContract.EXTRA_INITIAL_URI))

        val withInitial = OpenDocumentTreeAccess.createPickerIntent(treeUri())
        val initial = requireNotNull(
            @Suppress("DEPRECATION")
            withInitial.getParcelableExtra(DocumentsContract.EXTRA_INITIAL_URI) as Uri?,
        )
        assertEquals(treeUri(), initial.toString())
        assertTrue(DocumentsContract.isTreeUri(initial))
        assertFalse(
            OpenDocumentTreeAccess.createPickerIntent("file:///storage/emulated/0/Download")
                .hasExtra(DocumentsContract.EXTRA_INITIAL_URI),
        )

        assertEquals(PersistedSaveLocation.DEFAULT, store.read())
        assertEquals(
            SaveLocationPickerResult.Canceled,
            coordinator.applyPickerResult(0, treeUri(), StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS),
        )
        assertEquals(PersistedSaveLocation.DEFAULT, store.read())

        val invalid = coordinator.applyPickerResult(
            StorageAccessPolicy.RESULT_OK,
            SdmTestDocumentsProvider.documentUri(SdmTestDocumentsProvider.ROOT_DOC_ID).toString(),
        )
        assertTrue(invalid is SaveLocationPickerResult.Failed)
        assertEquals(PersistedSaveLocation.DEFAULT, store.read())

        val denied = coordinator.applyPickerResult(
            StorageAccessPolicy.RESULT_OK,
            treeUri(),
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
        )
        assertEquals(SaveLocationPickerResult.Failed("Could not keep access to that folder"), denied)
        assertNull(store.read().treeUri)
        assertFalse(grants.hasReadWrite(treeUri()))

        grants.takeSucceeds = true
        val accepted = coordinator.applyPickerResult(
            StorageAccessPolicy.RESULT_OK,
            " ${treeUri()} ",
            StorageAccessPolicy.TAKE_PERSISTABLE_FLAGS,
        )
        assertEquals(SaveLocationPickerResult.Accepted("SDM Test Documents"), accepted)
        assertEquals(treeUri(), store.read().treeUri)
        assertEquals("SDM Test Documents", store.read().displayLabel)
        assertTrue(grants.hasReadWrite(treeUri()))
        assertTrue(preferences.edit().commit())

        val restored = SaveLocationStore(preferences)
        val restoredCoordinator = SaveLocationCoordinator(restored, grants, trees)
        assertEquals(treeUri(), restored.read().treeUri)
        assertNull(restoredCoordinator.validatePersisted())
        val resolved = restoredCoordinator.resolveForNewDownload()
        assertNull(resolved.recovery)
        assertEquals(ActiveSaveLocation.UserTree(treeUri(), "SDM Test Documents"), resolved.location)
    }

    @Test
    fun filenameCollisionAllocatesAndPublishesWithoutOverwritingExistingDocument() {
        persistGrantedTree()
        seedTreeDocument("report.pdf", ORIGINAL_BYTES, "application/pdf")
        val beforeNames = childDisplayNames()
        assertTrue("report.pdf" in beforeNames)

        val allocated = allocator().allocate("report.pdf")
        assertEquals("report (1).pdf", allocated.fileName)
        assertEquals(treeUri(), allocated.destinationTreeUri)
        assertEquals("SDM Test Documents", allocated.destinationDisplayLabel)
        assertEquals(
            File(SaveLocationDestinationAllocator.stagingDirectory(downloadsDir), "report (1).pdf").absolutePath,
            allocated.destinationPath,
        )
        assertTrue(allocated.partFile.exists())
        assertEquals(ORIGINAL_BYTES, readDocument(documentUriFor("report.pdf")))

        val finalized = File(allocated.destinationPath).apply { writeText(COLLISION_BYTES) }
        val collisionRecord = completed(
            id = "collision",
            fileName = allocated.fileName,
            destination = allocated.destinationPath,
        )
        assertEquals("report (1).pdf", collisionRecord.fileName)
        assertTrue(' ' in collisionRecord.fileName)
        assertFalse(collisionRecord.url.any { it.isWhitespace() })
        assertTrue(DownloadUrl.validate(collisionRecord.url) is DownloadUrlResult.Valid)

        val published = publisher().afterLocalFinalize(collisionRecord, finalized)
        assertEquals("report (1).pdf", published.fileName)
        assertEquals(treeUri(), published.destinationTreeUri)
        assertEquals(ORIGINAL_BYTES, readDocument(documentUriFor("report.pdf")))
        assertEquals(COLLISION_BYTES, readDocument(Uri.parse(published.destinationPath)))
        assertNotEquals(documentUriFor("report.pdf").toString(), published.destinationPath)
        assertFalse(finalized.exists())
        assertTrue("report.pdf" in childDisplayNames())
        assertTrue("report (1).pdf" in childDisplayNames())
        assertEquals(treeUri(), store.read().treeUri)
    }

    @Test
    fun openAndShareIntentsGrantReadForCreatedContentDocument() {
        persistGrantedTree()
        val created = seedTreeDocument("clip.mp3", "audio-bytes", "audio/mpeg")
        val identity = CompletedFileIdentity(
            downloadId = "clip",
            destinationPath = created.toString(),
            persistedMimeType = "application/octet-stream",
            fileName = "clip.mp3",
        )
        assertTrue(CompletedFileDestination.classify(created.toString()) is CompletedFileDestinationKind.ContentDocument)
        assertEquals("audio-bytes", readDocument(created))

        val captured = mutableListOf<CompletedFileIntentSpec>()
        val opener = CompletedFileOpener(
            resolve = { current ->
                when (val destination = CompletedFileDestination.classify(current.destinationPath)) {
                    is CompletedFileDestinationKind.ContentDocument -> ShareableCompletedFile(
                        uriString = destination.uriString,
                        persistedMimeType = current.persistedMimeType,
                        fileName = current.fileName,
                        contentResolverType = context.contentResolver.getType(Uri.parse(destination.uriString)),
                    )
                    is CompletedFileDestinationKind.LocalFile,
                    CompletedFileDestinationKind.Unavailable,
                    -> null
                }
            },
            extensionMime = { extension -> MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) },
            hasHandler = { true },
            start = { captured += it },
        )

        assertEquals(CompletedFileActionResult.Launched, opener.perform(CompletedFileAction.Open, identity))
        assertEquals(CompletedFileActionResult.Launched, opener.perform(CompletedFileAction.Share, identity))
        assertEquals(2, captured.size)

        val openIntent = captured[0].toAndroidIntent()
        assertEquals(Intent.ACTION_VIEW, openIntent.action)
        assertEquals(created, openIntent.data)
        assertEquals("content", openIntent.data?.scheme)
        assertEquals("audio/mpeg", openIntent.type)
        assertTrue(openIntent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, openIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(created, openIntent.clipData?.getItemAt(0)?.uri)

        val shareIntent = captured[1].toAndroidIntent()
        assertEquals(Intent.ACTION_SEND, shareIntent.action)
        assertNull(shareIntent.data)
        assertEquals("audio/mpeg", shareIntent.type)
        val stream = requireNotNull(
            @Suppress("DEPRECATION")
            shareIntent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?,
        )
        assertEquals(created, stream)
        assertEquals(created, shareIntent.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, shareIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(CompletedFileIntents.CHOOSER_TITLE, captured[1].chooserTitle)
        assertFalse(captured.any { it.dataUri?.startsWith("file:") == true || it.extraStreamUri?.startsWith("file:") == true })
    }

    @Test
    fun renameAndDeleteKeepRepositoryConsistentWithDocumentsContract() = runBlocking {
        persistGrantedTree()
        val created = seedTreeDocument("original.bin", "payload", "application/octet-stream")
        val repository = isolatedRepository()
        repository.insert(
            completed(
                id = "saf",
                fileName = "original.bin",
                destination = created.toString(),
            ),
        )
        val documents = contentDocuments(granted = true)

        val renamed = DownloadRenameCoordinator.rename(
            downloadId = "saf",
            rawFilename = "renamed.bin",
            repository = repository,
            clock = FixedClock(9_000L),
            contentDocuments = documents,
        ) as DownloadRenameResult.Success
        assertEquals("renamed.bin", renamed.download.fileName)
        assertEquals("renamed.bin", repository.get("saf")!!.fileName)
        assertEquals(renamed.download.destinationPath, repository.get("saf")!!.destinationPath)
        assertNotEquals(created.toString(), renamed.download.destinationPath)
        assertEquals("payload", readDocument(Uri.parse(renamed.download.destinationPath)))
        assertFalse(documentExists(created))
        assertEquals(treeUri(), repository.get("saf")!!.destinationTreeUri)

        repository.close()
        val reopened = isolatedRepository(databaseName!!)
        assertEquals("renamed.bin", reopened.get("saf")!!.fileName)
        assertEquals(renamed.download.destinationPath, reopened.get("saf")!!.destinationPath)

        val deleted = CompletedFileDeleteCoordinator.delete("saf", reopened, documents)
        assertTrue(deleted is CompletedFileDeleteResult.Deleted)
        assertFalse(documentExists(Uri.parse(renamed.download.destinationPath)))
        assertNull(reopened.get("saf"))

        reopened.close()
        val afterDelete = isolatedRepository(databaseName!!)
        assertNull(afterDelete.get("saf"))
        assertTrue(afterDelete.downloads.value.none { it.id == "saf" })
    }

    @Test
    fun lostOrRevokedTreeAccessFallsBackWithoutDeletingRecordOrWritingToTree() = runBlocking {
        persistGrantedTree()
        val created = seedTreeDocument("locked.bin", "secret", "application/octet-stream")
        val namesBeforeRevoke = childDisplayNames()
        val repository = isolatedRepository()
        repository.insert(
            completed(
                id = "locked",
                fileName = "locked.bin",
                destination = created.toString(),
            ),
        )

        grants.granted.remove(treeUri())
        assertEquals(SaveLocationRecovery.PermissionRevoked, coordinator.validatePersisted())
        assertNull(store.read().treeUri)
        assertEquals(SaveLocationLabels.DEFAULT, store.read().displayLabel)
        assertTrue(documentExists(created))
        assertEquals("locked.bin", repository.get("locked")!!.fileName)

        val revokedDocuments = contentDocuments(granted = false)
        val deleteResult = CompletedFileDeleteCoordinator.delete("locked", repository, revokedDocuments)
        assertEquals(CompletedFileUserMessages.ACCESS_UNAVAILABLE, deleteResult.message)
        val renameResult = DownloadRenameCoordinator.rename(
            downloadId = "locked",
            rawFilename = "moved.bin",
            repository = repository,
            clock = FixedClock(4_000L),
            contentDocuments = revokedDocuments,
        ) as DownloadRenameResult.Failure
        assertEquals(CompletedFileUserMessages.ACCESS_UNAVAILABLE, renameResult.message)
        val reconciled = CompletedFileReconciliation.reconcile(
            records = repository.downloads.value,
            repository = repository,
            contentDocuments = revokedDocuments,
        )
        assertTrue(reconciled.prunedIds.isEmpty())
        assertEquals(setOf("locked"), reconciled.unavailableIds)
        assertEquals("locked.bin", repository.get("locked")!!.fileName)
        assertEquals(created.toString(), repository.get("locked")!!.destinationPath)
        assertEquals("secret", readDocument(created))

        persistGrantedTree()
        val namesBeforeInaccessibleWrite = childDisplayNames()
        SdmTestDocumentsProvider.accessRevoked = true
        val revokedAllocate = allocator().allocate("no-write.bin")
        assertNull(revokedAllocate.destinationTreeUri)
        assertEquals(File(downloadsDir, "no-write.bin").absolutePath, revokedAllocate.destinationPath)
        assertNull(store.read().treeUri)

        persistGrantedTree()
        SdmTestDocumentsProvider.accessRevoked = true
        val staged = File(
            SaveLocationDestinationAllocator.stagingDirectory(downloadsDir),
            "kept.bin",
        ).apply {
            parentFile?.mkdirs()
            writeText("kept")
        }
        val published = publisher().afterLocalFinalize(
            completed(
                id = "kept",
                fileName = "kept.bin",
                destination = staged.absolutePath,
            ),
            staged,
        )
        assertNull(published.destinationTreeUri)
        assertEquals(File(downloadsDir, "kept.bin").absolutePath, published.destinationPath)
        assertEquals("kept", File(downloadsDir, "kept.bin").readText())
        SdmTestDocumentsProvider.accessRevoked = false
        assertEquals(namesBeforeInaccessibleWrite, childDisplayNames())
        assertFalse("no-write.bin" in childDisplayNames())
        assertFalse("kept.bin" in childDisplayNames())
        assertTrue(documentExists(created))
        assertEquals("secret", readDocument(created))
        assertEquals("locked.bin", repository.get("locked")!!.fileName)

        val missingTree = SdmTestDocumentsProvider.treeUriFor("missing").toString()
        store.persistUserTree(missingTree, "Gone")
        grants.granted.add(missingTree)
        val lost = allocator().allocate("lost.bin")
        assertNull(lost.destinationTreeUri)
        assertEquals(File(downloadsDir, "lost.bin").absolutePath, lost.destinationPath)
        assertEquals(SaveLocationLabels.DEFAULT, store.read().displayLabel)
        assertNull(store.read().treeUri)
        assertEquals(namesBeforeRevoke, childDisplayNames())
        assertTrue(documentExists(created))
        assertEquals("locked.bin", repository.get("locked")!!.fileName)
    }

    private fun persistGrantedTree() {
        grants.takeSucceeds = true
        grants.granted.add(treeUri())
        store.persistUserTree(treeUri(), "SDM Test Documents")
    }

    private fun allocator(): SaveLocationDestinationAllocator = SaveLocationDestinationAllocator(
        coordinator = coordinator,
        appSpecificDirectory = { downloadsDir },
        trees = trees,
    )

    private fun publisher(): SafDownloadDestinationPublisher = SafDownloadDestinationPublisher(
        trees = trees,
        coordinator = coordinator,
        appSpecificDirectory = { downloadsDir },
    )

    private fun contentDocuments(granted: Boolean): DocumentsContractContentDocuments =
        DocumentsContractContentDocuments(
            contentResolver = context.contentResolver,
            hasTreeWriteGrant = { uri -> granted && uri == treeUri() },
        )

    private fun isolatedRepository(name: String = "sdm-045-${UUID.randomUUID()}.db"): SqliteDownloadRepository {
        repository?.close()
        databaseName = name
        return SqliteDownloadRepository(context, databaseName = name).also { created ->
            repository = created
            runBlocking { created.awaitInitialized() }
        }
    }

    private fun seedTreeDocument(name: String, contents: String, mimeType: String): Uri {
        val created = trees.createFile(treeUri(), mimeType, name)
        val source = File(downloadsDir, "seed-$name").apply { writeText(contents) }
        trees.writeFrom(created.documentUri, source)
        source.delete()
        return Uri.parse(created.documentUri)
    }

    private fun childDisplayNames(): Set<String> {
        val tree = SdmTestDocumentsProvider.treeUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        context.contentResolver.query(
            children,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        ).use { cursor ->
            if (cursor == null) return emptySet()
            val names = LinkedHashSet<String>()
            val index = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            if (index < 0) return emptySet()
            while (cursor.moveToNext()) {
                cursor.getString(index)
                    ?.takeIf { it.isNotBlank() && !it.startsWith(".sdm-write-probe-") }
                    ?.let(names::add)
            }
            return names
        }
    }

    private fun documentUriFor(displayName: String): Uri {
        val tree = SdmTestDocumentsProvider.treeUri()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            tree,
            DocumentsContract.getTreeDocumentId(tree),
        )
        context.contentResolver.query(
            children,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            ),
            null,
            null,
            null,
        ).use { cursor ->
            checkNotNull(cursor)
            val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == displayName) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex))
                }
            }
        }
        error("Missing test document $displayName")
    }

    private fun readDocument(uri: Uri): String =
        context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }

    private fun documentExists(uri: Uri): Boolean = try {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
    } catch (_: Exception) {
        false
    }

    private fun treeUri(): String = SdmTestDocumentsProvider.treeUri().toString()

    private fun fixtureDownloadUrl(fileName: String): String =
        URI("https", "example.com", "/$fileName", null).toASCIIString()

    private fun completed(
        id: String,
        fileName: String,
        destination: String,
        treeUri: String? = treeUri(),
    ) = Download(
        id = id,
        url = fixtureDownloadUrl(fileName),
        fileName = fileName,
        mimeType = "application/octet-stream",
        destinationPath = destination,
        destinationTreeUri = treeUri,
        destinationDisplayLabel = if (treeUri == null) null else "SDM Test Documents",
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

    private class IsolatedTreeUriGrantStore : TreeUriGrantStore {
        val granted = mutableSetOf<String>()
        var takeSucceeds = false

        override fun takeReadWrite(uriString: String, takeFlags: Int): PersistableGrantResult {
            val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
                ?: return PersistableGrantResult.InvalidTree(uriString, "invalid")
            if (!takeSucceeds) {
                return PersistableGrantResult.Failure(valid.uriString, "denied")
            }
            granted.add(valid.uriString)
            return PersistableGrantResult.Success(valid.uriString)
        }

        override fun releaseReadWrite(uriString: String): PersistableGrantResult {
            val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
                ?: return PersistableGrantResult.InvalidTree(uriString, "invalid")
            granted.remove(valid.uriString)
            return PersistableGrantResult.Success(valid.uriString)
        }

        override fun hasReadWrite(uriString: String): Boolean {
            val valid = StorageAccessPolicy.validateTreeUri(uriString) as? TreeUriValidation.Valid
                ?: return false
            return granted.contains(valid.uriString)
        }
    }

    companion object {
        private const val REAL_SETTINGS = "sdm_settings"
        private const val TEST_PREFS = "sdm-045-save-location"
        private const val TEST_DOWNLOADS = "sdm-045-downloads"
        private const val ORIGINAL_BYTES = "original-bytes"
        private const val COLLISION_BYTES = "collision-bytes"
    }
}
