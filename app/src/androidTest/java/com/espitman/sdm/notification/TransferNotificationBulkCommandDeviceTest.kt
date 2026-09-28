package com.espitman.sdm.notification

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadQueueScheduler
import com.espitman.sdm.download.DownloadTransferCommand
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.download.PauseAllCommand
import com.espitman.sdm.download.QueuedTransferStarter
import com.espitman.sdm.download.ResumeAllCommand
import com.espitman.sdm.download.TransferServiceLifecyclePolicy
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TransferNotificationBulkCommandDeviceTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var repository: SqliteDownloadRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "bulk-notification-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        repository?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun notificationBulkIntentsPauseAndResumeOnlyEligibleRowsOnIsolatedRepository() = runBlocking {
        val repo = SqliteDownloadRepository(context, databaseName = databaseName).also { repository = it }
        repo.awaitInitialized()
        listOf(
            item("queued", DownloadState.QUEUED, downloadedBytes = 8L, createdAt = 1L),
            item("connecting", DownloadState.CONNECTING, downloadedBytes = 3L, createdAt = 2L),
            item("paused", DownloadState.PAUSED, downloadedBytes = 12L, createdAt = 3L),
            item("failed", DownloadState.FAILED, downloadedBytes = 6L, createdAt = 4L, error = "stale"),
            item("cancelled", DownloadState.CANCELLED, downloadedBytes = 7L, createdAt = 5L),
            item(
                "done",
                DownloadState.COMPLETED,
                downloadedBytes = 100L,
                totalBytes = 100L,
                createdAt = 6L,
                completedAt = 7L,
            ),
        ).forEach { repo.insert(it) }

        val started = CopyOnWriteArrayList<String>()
        val pausedActive = CopyOnWriteArrayList<String>()
        val scheduler = DownloadQueueScheduler(
            repository = repo,
            concurrentLimit = { 3 },
            starter = QueuedTransferStarter { download -> started += download.id },
        )

        val pauseAll = DownloadTransferCommand.parse(
            action = DownloadTransferCommand.ACTION_PAUSE_ALL,
            downloadId = null,
            tempFilePath = null,
        )
        val resumeAll = DownloadTransferCommand.parse(
            action = DownloadTransferCommand.ACTION_RESUME_ALL,
            downloadId = null,
            tempFilePath = null,
        )
        assertEquals(PauseAllCommand, pauseAll)
        assertEquals(ResumeAllCommand, resumeAll)
        assertFalse(TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(pauseAll))
        assertFalse(TransferServiceLifecyclePolicy.startQueueObserverBeforeHandling(resumeAll))
        assertEquals(
            DownloadTransferService.bulkControlIntent(context, DownloadTransferCommand.ACTION_PAUSE_ALL)?.action,
            DownloadTransferCommand.ACTION_PAUSE_ALL,
        )
        assertEquals(
            DownloadTransferService.bulkControlIntent(context, DownloadTransferCommand.ACTION_RESUME_ALL)?.action,
            DownloadTransferCommand.ACTION_RESUME_ALL,
        )

        scheduler.pauseAll { pausedActive += it }

        assertEquals(DownloadState.PAUSED, repo.get("queued")!!.state)
        assertEquals(8L, repo.get("queued")!!.downloadedBytes)
        assertEquals(DownloadState.CONNECTING, repo.get("connecting")!!.state)
        assertEquals(listOf("connecting"), pausedActive.toList())
        assertEquals(DownloadState.PAUSED, repo.get("paused")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("failed")!!.state)
        assertEquals("stale", repo.get("failed")!!.error)
        assertEquals(DownloadState.CANCELLED, repo.get("cancelled")!!.state)
        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertTrue(started.isEmpty())

        scheduler.resumeAll()

        assertEquals(DownloadState.QUEUED, repo.get("queued")!!.state)
        assertEquals(DownloadState.QUEUED, repo.get("paused")!!.state)
        assertEquals(DownloadState.CONNECTING, repo.get("connecting")!!.state)
        assertEquals(DownloadState.FAILED, repo.get("failed")!!.state)
        assertEquals(DownloadState.CANCELLED, repo.get("cancelled")!!.state)
        assertEquals(DownloadState.COMPLETED, repo.get("done")!!.state)
        assertEquals(setOf("queued", "paused"), started.toSet())
    }

    private fun item(
        id: String,
        state: DownloadState,
        downloadedBytes: Long,
        createdAt: Long,
        totalBytes: Long = 100L,
        error: String? = null,
        completedAt: Long? = null,
    ) = Download(
        id = id,
        url = "https://127.0.0.1/$id.bin",
        fileName = "$id.bin",
        destinationPath = "/downloads/$id.bin",
        totalBytes = totalBytes,
        downloadedBytes = downloadedBytes,
        state = state,
        error = error,
        createdAtEpochMillis = createdAt,
        updatedAtEpochMillis = createdAt,
        completedAtEpochMillis = completedAt,
    )
}
