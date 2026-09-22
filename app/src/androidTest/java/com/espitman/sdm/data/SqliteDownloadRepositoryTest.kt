package com.espitman.sdm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SqliteDownloadRepositoryTest {
    private lateinit var context: Context
    private lateinit var databaseName: String
    private var repository: SqliteDownloadRepository? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "test-${UUID.randomUUID()}.db"
    }

    @After
    fun tearDown() {
        repository?.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun recordSurvivesRepositoryRecreationAndFlowReflectsAtomicChanges() = runBlocking {
        val initial = Download(
            id = "one",
            url = "https://example.com/one.bin",
            fileName = "one.bin",
            totalBytes = 10,
            createdAtEpochMillis = 100,
        )
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(initial)
        repository!!.transition("one", DownloadState.CONNECTING, 200)
        repository!!.transition("one", DownloadState.DOWNLOADING, 300)
        repository!!.updateProgress("one", 4, 400)
        assertEquals(4, repository!!.downloads.value.single().downloadedBytes)

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        assertEquals(4, repository!!.downloads.value.single().downloadedBytes)
        assertEquals(DownloadState.DOWNLOADING, repository!!.get("one")!!.state)
    }

    @Test
    fun invalidTransitionDoesNotPartiallyUpdateStoredRecord() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "one",
                url = "https://example.com/one.bin",
                fileName = "one.bin",
                createdAtEpochMillis = 100,
            ),
        )

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository!!.transition("one", DownloadState.COMPLETED, 200) }
        }
        assertEquals(DownloadState.QUEUED, repository!!.get("one")!!.state)
    }

    @Test
    fun versionOneDatabaseMigratesWithoutLosingRecords() = runBlocking {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { db ->
            DownloadDatabase.createVersionOne(db)
            db.execSQL(
                """INSERT INTO downloads
                    (id,url,file_name,downloaded_bytes,state,priority,created_at,updated_at)
                    VALUES ('legacy','https://example.com/legacy','legacy',0,'QUEUED',0,10,10)
                """.trimIndent(),
            )
            db.version = 1
        }

        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        assertEquals("legacy", repository!!.downloads.value.single().id)
        assertEquals(DownloadDatabase.DATABASE_VERSION, SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { it.version })
    }

    @Test
    fun concurrentConflictingTransitionsAreSerializedAndOnlyOneCommits() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "race",
                url = "https://example.com/race.bin",
                fileName = "race.bin",
                createdAtEpochMillis = 100,
            ),
        )

        val outcomes = listOf(200L, 201L).map { time ->
            async { runCatching { repository!!.transition("race", DownloadState.CONNECTING, time) } }
        }.awaitAll()

        assertEquals(1, outcomes.count { it.isSuccess })
        assertEquals(1, outcomes.count { it.isFailure })
        assertEquals(DownloadState.CONNECTING, repository!!.get("race")!!.state)
    }

    @Test
    fun staleProgressAndGenericOverwriteCannotCorruptRecord() = runBlocking {
        val item = Download(
            id = "progress",
            url = "https://example.com/progress.bin",
            fileName = "progress.bin",
            totalBytes = 100,
            createdAtEpochMillis = 100,
        )
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(item)
        repository!!.transition("progress", DownloadState.CONNECTING, 200)
        repository!!.transition("progress", DownloadState.DOWNLOADING, 300)
        repository!!.updateProgress("progress", 40, 400)

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository!!.updateProgress("progress", 20, 500) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository!!.updateProgress("progress", 50, 399) }
        }
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository!!.insert(item.copy(state = DownloadState.PAUSED, updatedAtEpochMillis = 600)) }
        }
        assertEquals(40, repository!!.get("progress")!!.downloadedBytes)

        val paused = repository!!.pauseAtExactOffset("progress", fileLengthBytes = 20, nowEpochMillis = 399)
        assertEquals(DownloadState.PAUSED, paused!!.state)
        assertEquals(20L, paused.downloadedBytes)
        assertEquals(400L, paused.updatedAtEpochMillis)
        assertEquals(null, paused.error)
        assertEquals(paused, repository!!.pauseAtExactOffset("progress", fileLengthBytes = 1, nowEpochMillis = 800))
        assertNull(repository!!.pauseAtExactOffset("missing", fileLengthBytes = 10, nowEpochMillis = 800))
    }

    @Test
    fun togglePriorityPersistsAtomicallyAndSurvivesRecreation() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "prio",
                url = "https://example.com/prio.bin",
                fileName = "prio.bin",
                createdAtEpochMillis = 100,
            ),
        )
        val high = repository!!.togglePriority("prio", 150)
        assertEquals(1, high!!.priority)
        assertEquals(150L, high.updatedAtEpochMillis)
        assertEquals(1, repository!!.downloads.value.single().priority)

        val again = repository!!.togglePriority("prio", 140)
        assertEquals(0, again!!.priority)
        assertEquals(150L, again.updatedAtEpochMillis)

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(0, repository!!.get("prio")!!.priority)

        repository!!.transition("prio", DownloadState.CANCELLED, 200)
        val cancelled = repository!!.togglePriority("prio", 300)
        assertEquals(0, cancelled!!.priority)
        assertEquals(DownloadState.CANCELLED, cancelled.state)
        assertEquals(200L, cancelled.updatedAtEpochMillis)
        assertNull(repository!!.togglePriority("missing", 400))
    }
}
