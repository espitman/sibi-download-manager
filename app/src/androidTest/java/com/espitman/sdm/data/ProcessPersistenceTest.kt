package com.espitman.sdm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.domain.Download
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** These methods are intentionally run through separate instrumentation invocations. */
@RunWith(AndroidJUnit4::class)
class ProcessPersistenceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun seedOrVerifyAcrossSeparateInstrumentationProcesses() = runBlocking {
        when (InstrumentationRegistry.getArguments().getString("persistencePhase")) {
            "seed" -> {
                context.deleteDatabase(DATABASE_NAME)
                SqliteDownloadRepository(context, databaseName = DATABASE_NAME).use { repository ->
                    repository.awaitInitialized()
                    repository.insert(
                        Download(
                            id = "process-survivor",
                            url = "https://example.com/process.bin",
                            fileName = "process.bin",
                            createdAtEpochMillis = 100,
                        ),
                    )
                }
            }
            "verify" -> try {
                SqliteDownloadRepository(context, databaseName = DATABASE_NAME).use { repository ->
                    repository.awaitInitialized()
                    assertEquals("process-survivor", repository.downloads.value.single().id)
                }
            } finally {
                context.deleteDatabase(DATABASE_NAME)
            }
            else -> assumeTrue("Run with persistencePhase=seed or persistencePhase=verify", false)
        }
    }

    private companion object {
        const val DATABASE_NAME = "process-persistence-test.db"
    }
}
