package com.espitman.sdm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.network.DownloadMetadata
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DownloadLinkRefreshDeviceTest {
    @Test fun preservesPartialRestartsSafelyAndSurvivesRepositoryReopen() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "refresh-test-${UUID.randomUUID()}.db"
        val destination = File(context.cacheDir, "refresh-${UUID.randomUUID()}.bin")
        val part = DownloadPartFile.forDestination(destination)
        var repository = SqliteDownloadRepository(context, databaseName = name)
        try {
            repository.awaitInitialized(); part.writeBytes(ByteArray(40) { it.toByte() })
            val old = Download(url = "http://example.com/expired", fileName = destination.name, destinationPath = destination.path,
                state = DownloadState.FAILED, error = "HTTP 410", downloadedBytes = 40, totalBytes = 100, etag = "\"same\"",
                sortOrder = 55, priority = 1, createdAtEpochMillis = 1)
            repository.insert(old)
            val metadata = DownloadMetadata(url = "http://example.com/fresh", contentLength = 100, acceptsRanges = true, etag = "\"same\"")
            val updated = repository.refreshLink(old.id, old, metadata, false, 2)
            assertEquals(40L, part.length()); assertEquals(40L, updated.downloadedBytes); assertEquals(55L, updated.sortOrder)
            assertEquals(DownloadState.PAUSED, updated.state)
            repository.resumePaused(old.id, 3)
            repository.transition(old.id, DownloadState.CONNECTING, 4)
            repository.transition(old.id, DownloadState.DOWNLOADING, 5)
            repository.pauseAtExactOffset(old.id, 40, 6)
            val paused = repository.get(old.id)!!
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                repository.refreshLink(old.id, paused, metadata.copy(etag = "\"changed\""), false, 7)
            } }
            assertEquals(40L, part.length())
            val restarted = repository.refreshLink(old.id, paused, metadata.copy(etag = "\"changed\""), true, 8)
            assertFalse(part.exists()); assertEquals(0L, restarted.downloadedBytes); assertEquals(55L, restarted.sortOrder)
            assertEquals(destination.path, restarted.destinationPath)
            repository.close(); repository = SqliteDownloadRepository(context, databaseName = name)
            repository.awaitInitialized(); assertEquals(restarted, repository.get(old.id))
        } finally { repository.close(); context.deleteDatabase(name); part.delete(); destination.delete() }
    }
}
