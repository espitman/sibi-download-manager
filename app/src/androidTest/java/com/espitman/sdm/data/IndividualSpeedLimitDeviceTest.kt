package com.espitman.sdm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IndividualSpeedLimitDeviceTest {
    @Test fun persistsAcrossReopenPauseResumeAndUnlimited() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "speed-test-${UUID.randomUUID()}.db"
        var repository = SqliteDownloadRepository(context, databaseName = name)
        try {
            repository.awaitInitialized()
            val record = Download(url = "https://example.org/file", fileName = "file", createdAtEpochMillis = 1, state = DownloadState.PAUSED)
            repository.insert(record)
            repository.updateSpeedLimit(record.id, 500_000, 2)
            repository.resumePaused(record.id, 3)
            assertEquals(500_000L, repository.get(record.id)?.speedLimitBytesPerSecond)
            repository.close(); repository = SqliteDownloadRepository(context, databaseName = name)
            repository.awaitInitialized()
            assertEquals(500_000L, repository.get(record.id)?.speedLimitBytesPerSecond)
            repository.updateSpeedLimit(record.id, null, 4)
            repository.close(); repository = SqliteDownloadRepository(context, databaseName = name)
            repository.awaitInitialized()
            assertNull(repository.get(record.id)?.speedLimitBytesPerSecond)
        } finally { repository.close(); context.deleteDatabase(name) }
    }
}
