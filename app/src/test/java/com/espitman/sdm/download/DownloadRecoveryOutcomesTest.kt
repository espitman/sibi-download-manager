package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAutoRetryPolicy
import com.espitman.sdm.domain.DownloadFailure
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.ActiveSaveLocation
import com.espitman.sdm.storage.CompletedDestinationPresence
import com.espitman.sdm.storage.CompletedFileReconciliation
import com.espitman.sdm.storage.DownloadDestinationPublisher
import com.espitman.sdm.storage.FakeTreeUriGrantStore
import com.espitman.sdm.storage.FakeUserTreeAccess
import com.espitman.sdm.storage.InMemoryPreferences
import com.espitman.sdm.storage.SafDownloadDestinationPublisher
import com.espitman.sdm.storage.SaveLocationCoordinator
import com.espitman.sdm.storage.SaveLocationStore
import com.espitman.sdm.storage.StorageCapacity
import com.espitman.sdm.storage.StorageCapacityProbe
import com.espitman.sdm.storage.TransferSpacePreflight
import com.espitman.sdm.storage.UserTreeInspection
import com.espitman.sdm.storage.UserTreeState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.security.cert.X509Certificate
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

class DownloadRecoveryOutcomesTest {
    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tempDir = Files.createTempDirectory("sdm063-recovery").toFile()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun insufficientStorageFailsWithoutWritingThenManualRetryCompletes() = runBlocking {
        val payload = ByteArray(64) { 7 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "storage.bin")
        val tempFile = File(tempDir, "storage.part")
        val repo = ContractDownloadRepository(
            listOf(
                queued(
                    id = "storage",
                    destFile = destFile,
                    totalBytes = payload.size.toLong(),
                ),
            ),
        )
        val probe = MutableCapacityProbe(
            local = StorageCapacity.from(totalBytes = 100L, availableBytes = 8L),
        )
        var written = 0
        val writingEngine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            onChunkRead = { written += it },
            storageCapacity = probe,
        )

        writingEngine.executeTransfer("storage", server.url("/storage.bin").toString(), tempFile, repo)
        val failed = repo.get("storage")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify(failed.error))
        assertEquals(TransferSpacePreflight.INSUFFICIENT_STORAGE_ERROR, failed.error)
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed))
        assertEquals(0, written)
        assertFalse(destFile.exists())
        assertTrue(!tempFile.exists() || tempFile.length() == 0L)

        assertEquals(DownloadState.QUEUED, repo.retryFailed("storage", automatic = false, nowEpochMillis = 3_000L)!!.state)
        probe.local = StorageCapacity.from(totalBytes = 100L, availableBytes = 64L)
        engine(probe).executeTransfer("storage", server.url("/storage.bin").toString(), tempFile, repo)
        val completed = repo.get("storage")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertNull(completed.error)
        assertArrayEquals(payload, destFile.readBytes())
        assertFalse(tempFile.exists())
    }

    @Test
    fun midStreamEnospcKeepsPartialAndDoesNotAutoRetry() = runBlocking {
        val payload = ByteArray(8_192) { 3 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "enospc.bin")
        val tempFile = File(tempDir, "enospc.part")
        val repo = ContractDownloadRepository(listOf(queued("enospc", destFile, payload.size.toLong())))
        val engine = DownloadTransferEngine(
            okHttpClient = OkHttpClient(),
            ioDispatcher = Dispatchers.IO,
            bufferSizeBytes = 256,
            progressUpdateIntervalBytes = 256L,
            onChunkRead = {
                if (tempFile.length() >= 256L) {
                    throw IOException("No space left on device")
                }
            },
        )

        engine.executeTransfer("enospc", server.url("/enospc.bin").toString(), tempFile, repo)
        val failed = repo.get("enospc")!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.INSUFFICIENT_STORAGE, DownloadFailure.classify(failed.error))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed))
        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() >= 256L)
        assertTrue(failed.downloadedBytes < payload.size.toLong())
        assertFalse(destFile.exists())
    }

    @Test
    fun revokedTreeGrantPublishesLocallyAndClearsTheSaveLocation() = runBlocking {
        val payload = ByteArray(32) { 5 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val staging = File(tempDir, "staging").apply { mkdirs() }
        val destFile = File(staging, "revoked.bin")
        val appDir = File(tempDir, "app-downloads").apply { mkdirs() }
        val store = SaveLocationStore(InMemoryPreferences())
        val grants = FakeTreeUriGrantStore()
        val trees = FakeUserTreeAccess()
        store.persistUserTree(treeUri, "Download")
        trees.inspections[treeUri] = UserTreeInspection(UserTreeState.PermissionRevoked)
        val coordinator = SaveLocationCoordinator(store, grants, trees)
        val repo = ContractDownloadRepository(
            listOf(
                queued("revoked", destFile, payload.size.toLong()).copy(
                    destinationTreeUri = treeUri,
                    destinationDisplayLabel = "Download",
                ),
            ),
        )

        engine(
            destinationPublisher = SafDownloadDestinationPublisher(
                trees = trees,
                coordinator = coordinator,
                appSpecificDirectory = { appDir },
            ),
        ).executeTransfer("revoked", server.url("/revoked.bin").toString(), File(tempDir, "revoked.part"), repo)

        val completed = repo.get("revoked")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertNull(completed.destinationTreeUri)
        assertTrue(completed.destinationPath!!.startsWith(appDir.absolutePath))
        assertArrayEquals(payload, File(completed.destinationPath!!).readBytes())
        assertNull(store.read().treeUri)
        val recoveredLocation = coordinator.resolveForNewDownload()
        assertTrue(recoveredLocation.location is ActiveSaveLocation.AppSpecific)
        assertNull(recoveredLocation.recovery)
    }

    @Test
    fun http403DoesNotAutoRetryWhile503RecoversOnTheNextAttempt() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val forbiddenDest = File(tempDir, "forbidden.bin")
        val forbiddenPart = File(tempDir, "forbidden.part")
        val forbiddenRepo = ContractDownloadRepository(
            listOf(queued("forbidden", forbiddenDest, totalBytes = 8L)),
        )
        var forbiddenAttempts = 0
        DownloadAutoRetryRunner(forbiddenRepo, clock = AdjustableClock()).run("forbidden") {
            forbiddenAttempts++
            engine().executeTransfer(
                "forbidden",
                server.url("/forbidden.bin").toString(),
                forbiddenPart,
                forbiddenRepo,
            )
        }
        val forbidden = forbiddenRepo.get("forbidden")!!
        assertEquals(1, forbiddenAttempts)
        assertEquals(DownloadState.FAILED, forbidden.state)
        assertEquals(DownloadFailure.EXPIRED_LINK, DownloadFailure.classify(forbidden.error))
        assertTrue(forbidden.error!!.contains("403"))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(forbidden))
        assertFalse(forbiddenDest.exists())
        assertTrue(!forbiddenPart.exists() || forbiddenPart.length() == 0L)

        val payload = ByteArray(24) { 9 }
        server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val transientDest = File(tempDir, "transient.bin")
        val transientPart = File(tempDir, "transient.part")
        val transientRepo = ContractDownloadRepository(
            listOf(queued("transient", transientDest, payload.size.toLong())),
        )
        var transientAttempts = 0
        DownloadAutoRetryRunner(transientRepo, clock = AdjustableClock(), delayMillis = {}).run("transient") {
            transientAttempts++
            engine().executeTransfer(
                "transient",
                server.url("/transient.bin").toString(),
                transientPart,
                transientRepo,
            )
        }
        val recovered = transientRepo.get("transient")!!
        assertEquals(2, transientAttempts)
        assertEquals(DownloadState.COMPLETED, recovered.state)
        assertEquals(1, recovered.automaticRetryCount)
        assertArrayEquals(payload, transientDest.readBytes())
        assertEquals(sha256(payload), sha256(transientDest.readBytes()))
    }

    @Test
    fun tlsHandshakeFailureStaysFailedWithoutAutoRetry() = runBlocking {
        val destFile = File(tempDir, "tls.bin")
        val tempFile = File(tempDir, "tls.part")
        val repo = ContractDownloadRepository(listOf(queued("tls", destFile, totalBytes = 16L)))
        val httpUrl = server.url("/tls.bin")
        val httpsUrl = "https://127.0.0.1:${httpUrl.port}${httpUrl.encodedPath}"
        val engine = DownloadTransferEngine(
            okHttpClient = rejectingTlsClient(),
            ioDispatcher = Dispatchers.IO,
        )
        var attempts = 0
        DownloadAutoRetryRunner(repo, clock = AdjustableClock(), delayMillis = {}).run("tls") {
            attempts++
            engine.executeTransfer("tls", httpsUrl, tempFile, repo)
        }
        val failed = repo.get("tls")!!
        assertEquals(1, attempts)
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadFailure.OTHER, DownloadFailure.classify(failed.error))
        assertTrue(failed.error!!.contains("SSLHandshakeException") || failed.error!!.contains("Trust anchor"))
        assertFalse(DownloadAutoRetryPolicy.shouldAutomaticallyRetry(failed))
        assertFalse(destFile.exists())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun deletedPartRestartsFromZeroAndMatchesChecksum() = runBlocking {
        val payload = ByteArray(1_024) { index -> (index % 251).toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "deleted-part.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        val repo = ContractDownloadRepository(
            listOf(
                queued("deleted-part", destFile, payload.size.toLong()).copy(
                    etag = "\"file-v1\"",
                    downloadedBytes = 512L,
                ),
            ),
        )
        assertFalse(tempFile.exists())

        engine(progressUpdateIntervalBytes = 128L).executeTransfer(
            "deleted-part",
            server.url("/deleted-part.bin").toString(),
            tempFile,
            repo,
        )
        val completed = repo.get("deleted-part")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertEquals(payload.size.toLong(), completed.downloadedBytes)
        assertEquals("\"file-v1\"", completed.etag)
        assertArrayEquals(payload, destFile.readBytes())
        assertEquals(sha256(payload), sha256(destFile.readBytes()))
        assertFalse(tempFile.exists())
        assertEquals(1, server.requestCount)
        assertNull(server.takeRequest().getHeader(HttpRangeResume.HEADER_RANGE))
    }

    @Test
    fun truncatedPartResumesFromTheRemainingBytes() = runBlocking {
        val payload = ByteArray(64) { index -> (index + 3).toByte() }
        val prefix = payload.copyOf(24)
        server.enqueue(
            MockResponse()
                .setResponseCode(206)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes 24-63/64")
                .setHeader(HttpRangeResume.HEADER_ETAG, "\"file-v1\"")
                .setBody(Buffer().write(payload.copyOfRange(24, 64))),
        )
        val destFile = File(tempDir, "truncated.bin")
        val tempFile = DownloadPartFile.forDestination(destFile)
        tempFile.writeBytes(prefix)
        val repo = ContractDownloadRepository(
            listOf(
                queued("truncated", destFile, 64L).copy(
                    etag = "\"file-v1\"",
                    downloadedBytes = 48L,
                ),
            ),
        )

        engine(progressUpdateIntervalBytes = 8L).executeTransfer(
            "truncated",
            server.url("/truncated.bin").toString(),
            tempFile,
            repo,
        )
        val completed = repo.get("truncated")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertArrayEquals(payload, destFile.readBytes())
        assertEquals(sha256(payload), sha256(destFile.readBytes()))
        val request = server.takeRequest()
        assertEquals("bytes=24-", request.getHeader(HttpRangeResume.HEADER_RANGE))
        assertEquals("\"file-v1\"", request.getHeader(HttpRangeResume.HEADER_IF_RANGE))
    }

    @Test
    fun deletedDestinationFolderIsRecreatedOnFinalize() = runBlocking {
        val payload = ByteArray(16) { 2 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val folder = File(tempDir, "gone-folder").apply { mkdirs() }
        val destFile = File(folder, "recreated.bin")
        val tempFile = File(tempDir, "recreated.part")
        val repo = ContractDownloadRepository(listOf(queued("recreated", destFile, payload.size.toLong())))
        assertTrue(folder.delete())

        engine().executeTransfer("recreated", server.url("/recreated.bin").toString(), tempFile, repo)
        val completed = repo.get("recreated")!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertTrue(folder.isDirectory)
        assertArrayEquals(payload, destFile.readBytes())
    }

    @Test
    fun deletedCompletedFilesArePrunedWhileRevokedAccessIsKept() = runBlocking {
        val readable = File(tempDir, "keep.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val missing = File(tempDir, "deleted.bin")
        val repo = ContractDownloadRepository(
            listOf(
                completed("keep", readable.absolutePath),
                completed("deleted", missing.absolutePath),
                completed("revoked", "content://docs/document/locked").copy(
                    destinationTreeUri = "content://docs/tree/root",
                    destinationDisplayLabel = "Shared",
                ),
            ),
        )
        val presence = mapOf(
            "keep" to CompletedDestinationPresence.Readable,
            "deleted" to CompletedDestinationPresence.Missing,
            "revoked" to CompletedDestinationPresence.AccessUnavailable,
        )

        val result = CompletedFileReconciliation.reconcile(
            records = repo.downloads.value,
            repository = repo,
            classify = { presence.getValue(it.id) },
        )
        assertEquals(setOf("deleted"), result.prunedIds)
        assertEquals(setOf("keep"), result.readableIds)
        assertEquals(setOf("revoked"), result.unavailableIds)
        assertEquals(setOf("keep", "revoked"), repo.downloads.value.map { it.id }.toSet())
        assertTrue(readable.exists())
        assertNull(repo.get("deleted"))
    }

    @Test
    fun pausedResumeWithDeletedPartFailsThenManualRetryRestarts() = runBlocking {
        val payload = ByteArray(48) { 4 }
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))
        val destFile = File(tempDir, "paused-missing.bin")
        val paused = Download(
            id = "paused-missing",
            url = server.url("/paused-missing.bin").toString(),
            fileName = destFile.name,
            etag = "\"file-v1\"",
            destinationPath = destFile.absolutePath,
            totalBytes = payload.size.toLong(),
            downloadedBytes = 16L,
            state = DownloadState.PAUSED,
            createdAtEpochMillis = 1_000L,
            updatedAtEpochMillis = 2_000L,
        )
        val repo = ContractDownloadRepository(listOf(paused))
        val resolved = DownloadResumePart.resolve(paused.destinationPath)
        check(resolved is DownloadResumePart.Result.Failed)
        DownloadResumeFailure.persist(repo, paused.id, resolved.error, 3_000L)
        val failed = repo.get(paused.id)!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals("Incomplete download part is missing", failed.error)
        assertEquals(16L, failed.downloadedBytes)

        assertEquals(DownloadState.QUEUED, repo.retryFailed(paused.id, automatic = false, nowEpochMillis = 4_000L)!!.state)
        engine(progressUpdateIntervalBytes = 8L).executeTransfer(
            paused.id,
            paused.url,
            DownloadPartFile.forDestination(destFile),
            repo,
        )
        val completed = repo.get(paused.id)!!
        assertEquals(DownloadState.COMPLETED, completed.state)
        assertArrayEquals(payload, destFile.readBytes())
    }

    private fun engine(
        storageCapacity: StorageCapacityProbe = StorageCapacityProbe.Unknown,
        destinationPublisher: DownloadDestinationPublisher = DownloadDestinationPublisher.KeepLocal,
        progressUpdateIntervalBytes: Long = DownloadTransferEngine.DEFAULT_PROGRESS_UPDATE_INTERVAL_BYTES,
    ) = DownloadTransferEngine(
        okHttpClient = OkHttpClient(),
        ioDispatcher = Dispatchers.IO,
        storageCapacity = storageCapacity,
        destinationPublisher = destinationPublisher,
        progressUpdateIntervalBytes = progressUpdateIntervalBytes,
    )

    private fun queued(id: String, destFile: File, totalBytes: Long) = Download(
        id = id,
        url = server.url("/$id.bin").toString(),
        fileName = destFile.name,
        destinationPath = destFile.absolutePath,
        totalBytes = totalBytes,
        state = DownloadState.QUEUED,
        createdAtEpochMillis = 1_000L,
    )

    private fun completed(id: String, destinationPath: String) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        destinationPath = destinationPath,
        totalBytes = 4L,
        downloadedBytes = 4L,
        state = DownloadState.COMPLETED,
        createdAtEpochMillis = 1_000L,
        updatedAtEpochMillis = 1_000L,
        completedAtEpochMillis = 1_000L,
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun rejectingTlsClient(): OkHttpClient {
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val factory = object : SSLSocketFactory() {
            override fun getDefaultCipherSuites(): Array<String> = emptyArray()
            override fun getSupportedCipherSuites(): Array<String> = emptyArray()
            override fun createSocket(s: Socket, host: String, port: Int, autoClose: Boolean): Socket =
                throw SSLHandshakeException("Trust anchor for certification path not found")
            override fun createSocket(host: String, port: Int): Socket =
                throw SSLHandshakeException("Trust anchor for certification path not found")
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
                throw SSLHandshakeException("Trust anchor for certification path not found")
            override fun createSocket(host: InetAddress, port: Int): Socket =
                throw SSLHandshakeException("Trust anchor for certification path not found")
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
                throw SSLHandshakeException("Trust anchor for certification path not found")
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(factory, trustManager)
            .hostnameVerifier { _, _ -> false }
            .build()
    }

    private class MutableCapacityProbe(
        var local: StorageCapacity,
    ) : StorageCapacityProbe {
        override fun queryLocalPath(path: File): StorageCapacity = local
        override fun queryTree(treeUri: String): StorageCapacity = StorageCapacity.Unknown
    }
}
