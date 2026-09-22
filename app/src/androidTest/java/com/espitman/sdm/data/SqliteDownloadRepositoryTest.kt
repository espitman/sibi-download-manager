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
import java.time.LocalDate
import java.time.ZoneOffset

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
        assertEquals(0L, repository!!.transferredBytesForLocalDay(10L, ZoneOffset.UTC))
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDailyTransferTableExists()
    }

    @Test
    fun versionTwoDatabaseMigratesWithoutLosingRecords() = runBlocking {
        seedLegacyDatabase(2)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        assertEquals("legacy", repository!!.downloads.value.single().id)
        assertEquals(0L, repository!!.transferredBytesForLocalDay(10L, ZoneOffset.UTC))
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDailyTransferTableExists()
    }

    @Test
    fun versionThreeDatabaseMigratesWithoutLosingRecords() = runBlocking {
        seedLegacyDatabase(3)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        assertEquals("legacy", repository!!.downloads.value.single().id)
        assertEquals(0L, repository!!.transferredBytesForLocalDay(10L, ZoneOffset.UTC))
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDailyTransferTableExists()
    }

    @Test
    fun versionFourDatabaseMigratesWithoutLosingRecords() = runBlocking {
        seedLegacyDatabase(4)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        val migrated = repository!!.downloads.value.single()
        assertEquals("legacy", migrated.id)
        assertNull(migrated.acceptsRanges)
        assertEquals(0L, repository!!.transferredBytesForLocalDay(10L, ZoneOffset.UTC))
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDailyTransferTableExists()
        assertAcceptsRangesColumnExists()
    }

    @Test
    fun acceptsRangesRoundTripsNullTrueAndFalse() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "unknown",
                url = "https://example.com/unknown.bin",
                fileName = "unknown.bin",
                createdAtEpochMillis = 100,
                acceptsRanges = null,
            ),
        )
        repository!!.insert(
            Download(
                id = "supported",
                url = "https://example.com/supported.bin",
                fileName = "supported.bin",
                createdAtEpochMillis = 101,
                acceptsRanges = true,
            ),
        )
        repository!!.insert(
            Download(
                id = "unsupported",
                url = "https://example.com/unsupported.bin",
                fileName = "unsupported.bin",
                createdAtEpochMillis = 102,
                acceptsRanges = false,
            ),
        )

        fun values() = repository!!.downloads.value.associate { it.id to it.acceptsRanges }
        assertEquals(mapOf("unknown" to null, "supported" to true, "unsupported" to false), values())

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(mapOf("unknown" to null, "supported" to true, "unsupported" to false), values())
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
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

    @Test
    fun dailyTotalsCountOnlyPositiveDeltasAndSurviveRecreation() = runBlocking {
        val zone = ZoneOffset.UTC
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        startDownloading("progress", totalBytes = Long.MAX_VALUE, createdAt = 100)

        repository!!.updateProgress("progress", 40, 400)
        repository!!.updateProgress("progress", 40, 401)
        assertEquals(40L, repository!!.transferredBytesForLocalDay(400, zone))

        val paused = repository!!.pauseAtExactOffset("progress", fileLengthBytes = 55, nowEpochMillis = 500)
        assertEquals(55L, paused!!.downloadedBytes)
        assertEquals(55L, repository!!.transferredBytesForLocalDay(500, zone))

        repository!!.resumePaused("progress", 600)
        repository!!.togglePriority("progress", 700)
        repository!!.transition("progress", DownloadState.CONNECTING, 800)
        repository!!.beginFreshRestart("progress", 900, etag = null, lastModified = null, totalBytes = Long.MAX_VALUE)
        assertEquals(0L, repository!!.get("progress")!!.downloadedBytes)
        assertEquals(55L, repository!!.transferredBytesForLocalDay(900, zone))

        repository!!.transition("progress", DownloadState.DOWNLOADING, 1_000)
        repository!!.updateProgress("progress", 20, 1_100)
        val cancelled = repository!!.cancelAtExactOffset("progress", fileLengthBytes = 30, nowEpochMillis = 1_200)
        assertEquals(30L, cancelled!!.downloadedBytes)
        assertEquals(85L, repository!!.transferredBytesForLocalDay(1_200, zone))

        repository!!.close()
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        assertEquals(85L, repository!!.transferredBytesForLocalDay(1_200, zone))
    }

    @Test
    fun pauseAndCancelRewindsDoNotSubtractAndStateMutationsAddZero() = runBlocking {
        val zone = ZoneOffset.UTC
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        startDownloading("rewind", totalBytes = 100, createdAt = 100)
        repository!!.updateProgress("rewind", 40, 400)
        repository!!.pauseAtExactOffset("rewind", fileLengthBytes = 20, nowEpochMillis = 500)
        assertEquals(40L, repository!!.transferredBytesForLocalDay(500, zone))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { repository!!.transition("rewind", DownloadState.COMPLETED, 600) }
        }
        assertEquals(40L, repository!!.transferredBytesForLocalDay(600, zone))

        repository!!.resumePaused("rewind", 700)
        repository!!.transition("rewind", DownloadState.CONNECTING, 800)
        repository!!.transition("rewind", DownloadState.DOWNLOADING, 900)
        repository!!.updateProgress("rewind", 20, 1_000)
        repository!!.cancelAtExactOffset("rewind", fileLengthBytes = 10, nowEpochMillis = 1_100)
        assertEquals(40L, repository!!.transferredBytesForLocalDay(1_100, zone))
    }

    @Test
    fun dailyTotalsUseLocalDayOfMutationTimestampAndSaturate() = runBlocking {
        val zone = ZoneOffset.ofHours(3)
        val dayStart = LocalDate.of(2023, 11, 14).atStartOfDay(zone).toInstant().toEpochMilli()
        val yesterday = dayStart - 1L
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        startDownloading("overnight", totalBytes = Long.MAX_VALUE, createdAt = yesterday - 10)
        repository!!.updateProgress("overnight", 25, yesterday)
        startDownloading("today", totalBytes = Long.MAX_VALUE, createdAt = dayStart)
        repository!!.updateProgress("today", Long.MAX_VALUE - 1L, dayStart + 10)
        startDownloading("overflow", totalBytes = 10, createdAt = dayStart + 20)
        repository!!.updateProgress("overflow", 2, dayStart + 30)

        assertEquals(25L, repository!!.transferredBytesForLocalDay(yesterday, zone))
        assertEquals(Long.MAX_VALUE, repository!!.transferredBytesForLocalDay(dayStart, zone))
        assertEquals(0L, repository!!.transferredBytesForLocalDay(dayStart + 86_400_000L, zone))
    }

    private suspend fun startDownloading(id: String, totalBytes: Long, createdAt: Long) {
        repository!!.insert(
            Download(
                id = id,
                url = "https://example.com/$id.bin",
                fileName = "$id.bin",
                totalBytes = totalBytes,
                createdAtEpochMillis = createdAt,
            ),
        )
        repository!!.transition(id, DownloadState.CONNECTING, createdAt + 1)
        repository!!.transition(id, DownloadState.DOWNLOADING, createdAt + 2)
    }

    private fun seedLegacyDatabase(version: Int) {
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName), null).use { db ->
            DownloadDatabase.createVersionOne(db)
            if (version >= 2) DownloadDatabase.migrateOneToTwo(db)
            if (version >= 3) DownloadDatabase.migrateTwoToThree(db)
            if (version >= 4) DownloadDatabase.migrateThreeToFour(db)
            db.execSQL(
                """INSERT INTO downloads
                    (id,url,file_name,downloaded_bytes,state,priority,created_at,updated_at)
                    VALUES ('legacy','https://example.com/legacy','legacy',0,'QUEUED',0,10,10)
                """.trimIndent(),
            )
            db.version = version
        }
    }

    private fun openedVersion(): Int = SQLiteDatabase.openDatabase(
        context.getDatabasePath(databaseName).path,
        null,
        SQLiteDatabase.OPEN_READONLY,
    ).use { it.version }

    private fun assertAcceptsRangesColumnExists() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("PRAGMA table_info(downloads)", null).use { cursor ->
                val names = buildList {
                    val nameIndex = cursor.getColumnIndex("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
                assertEquals(true, names.contains("accepts_ranges"))
            }
        }
    }

    private fun assertDailyTransferTableExists() {
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY,
        ).use { db ->
            db.rawQuery("SELECT day_key, transferred_bytes FROM daily_transfer_totals", null).use { cursor ->
                assertEquals(0, cursor.count)
            }
        }
    }
}
