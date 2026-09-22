package com.espitman.sdm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadPauseCause
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
    companion object {
        private const val EMPTY_SHA256_HEX =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    }
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
        assertNull(migrated.referenceSha256)
        assertEquals(0L, repository!!.transferredBytesForLocalDay(10L, ZoneOffset.UTC))
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDailyTransferTableExists()
        assertAcceptsRangesColumnExists()
        assertReferenceSha256ColumnExists()
    }

    @Test
    fun versionFiveDatabaseMigratesWithoutLosingRecords() = runBlocking {
        seedLegacyDatabase(5)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        val migrated = repository!!.downloads.value.single()
        assertEquals("legacy", migrated.id)
        assertNull(migrated.referenceSha256)
        assertEquals(0, migrated.automaticRetryCount)
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertReferenceSha256ColumnExists()
    }

    @Test
    fun versionSixDatabaseMigratesAutomaticRetryCountToZero() = runBlocking {
        seedLegacyDatabase(6)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        val migrated = repository!!.downloads.value.single()
        assertEquals("legacy", migrated.id)
        assertEquals(0, migrated.automaticRetryCount)
        assertNull(migrated.destinationTreeUri)
        assertNull(migrated.destinationDisplayLabel)
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertAutomaticRetryCountColumnExists()
        assertDestinationColumnsExist()
    }

    @Test
    fun versionSevenDatabaseMigratesDestinationColumnsToNull() = runBlocking {
        seedLegacyDatabase(7)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        val migrated = repository!!.downloads.value.single()
        assertEquals("legacy", migrated.id)
        assertEquals(0, migrated.automaticRetryCount)
        assertNull(migrated.destinationTreeUri)
        assertNull(migrated.destinationDisplayLabel)
        assertNull(migrated.pauseCause)
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDestinationColumnsExist()
    }

    @Test
    fun versionEightDatabaseMigratesPauseCauseToNull() = runBlocking {
        seedLegacyDatabase(8)
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()

        val migrated = repository!!.downloads.value.single()
        assertEquals("legacy", migrated.id)
        assertNull(migrated.pauseCause)
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertPauseCauseColumnExists()
    }

    @Test
    fun pauseCauseRoundTripsNullAndNetworkPolicyAndSurvivesReopen() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "manual",
                url = "https://example.com/manual.bin",
                fileName = "manual.bin",
                createdAtEpochMillis = 100,
            ),
        )
        repository!!.insert(
            Download(
                id = "policy",
                url = "https://example.com/policy.bin",
                fileName = "policy.bin",
                createdAtEpochMillis = 101,
            ),
        )
        repository!!.transition("manual", DownloadState.CONNECTING, 200)
        repository!!.transition("policy", DownloadState.CONNECTING, 201)
        repository!!.pauseAtExactOffset("manual", fileLengthBytes = 0, nowEpochMillis = 300)
        repository!!.pauseAtExactOffset(
            "policy",
            fileLengthBytes = 0,
            nowEpochMillis = 301,
            pauseCause = DownloadPauseCause.NETWORK_POLICY,
        )

        fun values() = repository!!.downloads.value.associate { it.id to it.pauseCause }
        assertEquals(
            mapOf("manual" to null, "policy" to DownloadPauseCause.NETWORK_POLICY),
            values(),
        )

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(
            mapOf("manual" to null, "policy" to DownloadPauseCause.NETWORK_POLICY),
            values(),
        )

        val requeued = repository!!.requeueNetworkPolicyPaused(400)
        assertEquals(listOf("policy"), requeued.map { it.id })
        assertEquals(DownloadState.QUEUED, repository!!.get("policy")!!.state)
        assertNull(repository!!.get("policy")!!.pauseCause)
        assertEquals(DownloadState.PAUSED, repository!!.get("manual")!!.state)
        assertNull(repository!!.get("manual")!!.pauseCause)
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
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
    fun referenceSha256RoundTripsNullAndNormalizedHex() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "unknown",
                url = "https://example.com/unknown.bin",
                fileName = "unknown.bin",
                createdAtEpochMillis = 100,
                referenceSha256 = null,
            ),
        )
        repository!!.insert(
            Download(
                id = "hashed",
                url = "https://example.com/hashed.bin",
                fileName = "hashed.bin",
                createdAtEpochMillis = 101,
                referenceSha256 = EMPTY_SHA256_HEX,
            ),
        )

        fun values() = repository!!.downloads.value.associate { it.id to it.referenceSha256 }
        assertEquals(mapOf("unknown" to null, "hashed" to EMPTY_SHA256_HEX), values())

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(mapOf("unknown" to null, "hashed" to EMPTY_SHA256_HEX), values())
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertReferenceSha256ColumnExists()
    }

    @Test
    fun automaticRetryCountRoundTripsDefaultAndPositiveValues() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "fresh",
                url = "https://example.com/fresh.bin",
                fileName = "fresh.bin",
                createdAtEpochMillis = 100,
            ),
        )
        repository!!.insert(
            Download(
                id = "retried",
                url = "https://example.com/retried.bin",
                fileName = "retried.bin",
                createdAtEpochMillis = 101,
                automaticRetryCount = 3,
            ),
        )

        fun values() = repository!!.downloads.value.associate { it.id to it.automaticRetryCount }
        assertEquals(mapOf("fresh" to 0, "retried" to 3), values())

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(mapOf("fresh" to 0, "retried" to 3), values())
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertAutomaticRetryCountColumnExists()
        assertDestinationColumnsExist()
    }

    @Test
    fun destinationTreeUriAndLabelRoundTrip() = runBlocking {
        val treeUri = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "local",
                url = "https://example.com/local.bin",
                fileName = "local.bin",
                destinationPath = "/tmp/local.bin",
                createdAtEpochMillis = 100,
            ),
        )
        repository!!.insert(
            Download(
                id = "tree",
                url = "https://example.com/tree.bin",
                fileName = "tree.bin",
                destinationPath = "/tmp/staging/tree.bin",
                destinationTreeUri = treeUri,
                destinationDisplayLabel = "Download",
                createdAtEpochMillis = 101,
            ),
        )

        fun values() = repository!!.downloads.value.associate { it.id to (it.destinationTreeUri to it.destinationDisplayLabel) }
        assertEquals(
            mapOf("local" to (null to null), "tree" to (treeUri to "Download")),
            values(),
        )

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(
            mapOf("local" to (null to null), "tree" to (treeUri to "Download")),
            values(),
        )
        assertEquals(DownloadDatabase.DATABASE_VERSION, openedVersion())
        assertDestinationColumnsExist()
    }

    @Test
    fun retryFailedRequeuesOnlyFailedRecordsAndPreservesProgress() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "failed",
                url = "https://example.com/failed.bin",
                fileName = "failed.bin",
                destinationPath = "/tmp/failed.bin",
                totalBytes = 100,
                downloadedBytes = 40,
                state = DownloadState.FAILED,
                error = "HTTP 503: Service Unavailable",
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 400,
                automaticRetryCount = 1,
            ),
        )
        repository!!.insert(
            Download(
                id = "queued",
                url = "https://example.com/queued.bin",
                fileName = "queued.bin",
                createdAtEpochMillis = 100,
                automaticRetryCount = 2,
            ),
        )

        assertNull(repository!!.retryFailed("missing", automatic = true, nowEpochMillis = 500))
        val queuedUnchanged = repository!!.get("queued")!!
        assertNull(repository!!.retryFailed("queued", automatic = true, nowEpochMillis = 500))
        assertEquals(queuedUnchanged, repository!!.get("queued"))

        val automatic = repository!!.retryFailed("failed", automatic = true, nowEpochMillis = 300)!!
        assertEquals(DownloadState.QUEUED, automatic.state)
        assertNull(automatic.error)
        assertEquals(40L, automatic.downloadedBytes)
        assertEquals("/tmp/failed.bin", automatic.destinationPath)
        assertEquals(2, automatic.automaticRetryCount)
        assertEquals(400L, automatic.updatedAtEpochMillis)

        repository!!.transition("failed", DownloadState.CONNECTING, 500)
        repository!!.transition("failed", DownloadState.FAILED, 600, "timeout")
        val manual = repository!!.retryFailed("failed", automatic = false, nowEpochMillis = 700)!!
        assertEquals(0, manual.automaticRetryCount)
        assertEquals(DownloadState.QUEUED, manual.state)
        assertEquals(40L, manual.downloadedBytes)
        assertNull(manual.error)
        assertEquals(700L, manual.updatedAtEpochMillis)

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(0, repository!!.get("failed")!!.automaticRetryCount)
        assertEquals(DownloadState.QUEUED, repository!!.get("failed")!!.state)
    }

    @Test
    fun requeueInterruptedActiveRequeuesOnlyStaleActiveAndPreservesProgress() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "active",
                url = "https://example.com/active.bin",
                fileName = "active.bin",
                destinationPath = "/tmp/active.bin",
                totalBytes = 100,
                downloadedBytes = 0,
                createdAtEpochMillis = 100,
                automaticRetryCount = 2,
            ),
        )
        repository!!.insert(
            Download(
                id = "failed",
                url = "https://example.com/failed.bin",
                fileName = "failed.bin",
                destinationPath = "/tmp/failed.bin",
                totalBytes = 100,
                downloadedBytes = 40,
                state = DownloadState.FAILED,
                error = "HTTP 500: Internal Server Error",
                createdAtEpochMillis = 100,
                updatedAtEpochMillis = 400,
            ),
        )
        repository!!.insert(
            Download(
                id = "paused",
                url = "https://example.com/paused.bin",
                fileName = "paused.bin",
                createdAtEpochMillis = 100,
            ),
        )
        repository!!.transition("active", DownloadState.CONNECTING, 200)
        repository!!.transition("active", DownloadState.DOWNLOADING, 300)
        repository!!.updateProgress("active", 55, 350)
        repository!!.transition("paused", DownloadState.CONNECTING, 200)
        repository!!.pauseAtExactOffset("paused", fileLengthBytes = 9, nowEpochMillis = 300)

        assertNull(repository!!.requeueInterruptedActive("missing", nowEpochMillis = 500))
        assertNull(repository!!.requeueInterruptedActive("failed", nowEpochMillis = 500))
        assertEquals(DownloadState.FAILED, repository!!.get("failed")!!.state)
        assertEquals("HTTP 500: Internal Server Error", repository!!.get("failed")!!.error)
        assertNull(repository!!.requeueInterruptedActive("paused", nowEpochMillis = 500))
        assertEquals(DownloadState.PAUSED, repository!!.get("paused")!!.state)
        assertNull(repository!!.get("paused")!!.pauseCause)

        val recovered = repository!!.requeueInterruptedActive("active", nowEpochMillis = 250)!!
        assertEquals(DownloadState.QUEUED, recovered.state)
        assertNull(recovered.error)
        assertEquals(55L, recovered.downloadedBytes)
        assertEquals("/tmp/active.bin", recovered.destinationPath)
        assertEquals(2, recovered.automaticRetryCount)
        assertEquals(350L, recovered.updatedAtEpochMillis)
        assertNull(repository!!.requeueInterruptedActive("active", nowEpochMillis = 600))
        assertEquals(DownloadState.QUEUED, repository!!.get("active")!!.state)
        assertEquals(2, repository!!.get("active")!!.automaticRetryCount)

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(DownloadState.QUEUED, repository!!.get("active")!!.state)
        assertEquals(55L, repository!!.get("active")!!.downloadedBytes)
        assertEquals(2, repository!!.get("active")!!.automaticRetryCount)
        assertEquals(DownloadState.FAILED, repository!!.get("failed")!!.state)
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
    fun shorterPartialAlignmentPersistsAcrossRepositoryRecreation() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(
            Download(
                id = "shortened-part",
                url = "https://example.com/shortened-part.bin",
                fileName = "shortened-part.bin",
                totalBytes = 100,
                createdAtEpochMillis = 100,
            ),
        )
        repository!!.transition("shortened-part", DownloadState.CONNECTING, 200)
        repository!!.transition("shortened-part", DownloadState.DOWNLOADING, 300)
        repository!!.updateProgress("shortened-part", 80, 400)

        val aligned = repository!!.alignDownloadedBytes("shortened-part", 25, 500)
        assertEquals(25L, aligned.downloadedBytes)
        assertEquals(DownloadState.DOWNLOADING, aligned.state)
        assertEquals(500L, aligned.updatedAtEpochMillis)

        repository!!.close()
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        assertEquals(25L, repository!!.get("shortened-part")!!.downloadedBytes)
        assertEquals(25L, repository!!.alignDownloadedBytes("shortened-part", 90, 600).downloadedBytes)
        assertEquals(60L, repository!!.updateProgress("shortened-part", 60, 700).downloadedBytes)
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
    fun moveToTopReordersQueuedPeersWithoutChangingPriorityOrDailyBytes() = runBlocking {
        val zone = ZoneOffset.UTC
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        repository!!.insert(queued("first", createdAt = 100, sortOrder = 0))
        repository!!.insert(queued("second", createdAt = 200, sortOrder = 0))
        repository!!.insert(queued("high", createdAt = 50, sortOrder = 0, priority = 1))
        startDownloading("active", totalBytes = 100, createdAt = 10)
        repository!!.updateProgress("active", 40, 400)
        assertEquals(40L, repository!!.transferredBytesForLocalDay(400, zone))

        assertNull(repository!!.moveToTop("missing", 500))
        val paused = repository!!.pauseAtExactOffset("active", fileLengthBytes = 40, nowEpochMillis = 500)
        val pausedMove = repository!!.moveToTop("active", 600)
        assertEquals(paused, pausedMove)
        assertEquals(DownloadState.PAUSED, pausedMove!!.state)

        val alreadyFirst = repository!!.moveToTop("first", 700)
        assertEquals(0L, alreadyFirst!!.sortOrder)
        assertEquals(100L, alreadyFirst.updatedAtEpochMillis)

        val moved = repository!!.moveToTop("second", 800)
        assertEquals(-1L, moved!!.sortOrder)
        assertEquals(800L, moved.updatedAtEpochMillis)
        assertEquals(0, moved.priority)
        assertEquals(DownloadState.QUEUED, moved.state)
        assertEquals(listOf("high", "second", "first"), queuedIds())
        assertEquals(1, repository!!.get("high")!!.priority)
        assertEquals(0L, repository!!.get("high")!!.sortOrder)
        assertEquals(40L, repository!!.transferredBytesForLocalDay(800, zone))

        repository!!.close()
        repository = SqliteDownloadRepository(
            context,
            databaseName = databaseName,
            localZoneId = zone,
        )
        repository!!.awaitInitialized()
        assertEquals(-1L, repository!!.get("second")!!.sortOrder)
        assertEquals(listOf("high", "second", "first"), queuedIds())
        assertEquals(40L, repository!!.transferredBytesForLocalDay(800, zone))
    }

    @Test
    fun moveToTopRebasesQueuedSortOrdersWhenMinimumIsLongMinValue() = runBlocking {
        repository = SqliteDownloadRepository(context, databaseName = databaseName)
        repository!!.awaitInitialized()
        repository!!.insert(queued("first", createdAt = 30, sortOrder = Long.MIN_VALUE))
        repository!!.insert(queued("second", createdAt = 40, sortOrder = Long.MIN_VALUE))
        repository!!.insert(queued("third", createdAt = 10, sortOrder = 9))
        repository!!.insert(queued("other-priority", createdAt = 1, sortOrder = Long.MIN_VALUE, priority = 1))

        val moved = repository!!.moveToTop("third", 2_000)
        assertEquals(0L, moved!!.sortOrder)
        assertEquals(2_000L, moved.updatedAtEpochMillis)
        assertEquals(listOf("other-priority", "third", "first", "second"), queuedIds())
        assertEquals(1L, repository!!.get("first")!!.sortOrder)
        assertEquals(2L, repository!!.get("second")!!.sortOrder)
        assertEquals(Long.MIN_VALUE, repository!!.get("other-priority")!!.sortOrder)
        assertEquals(1, repository!!.get("other-priority")!!.priority)
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

    private fun queued(
        id: String,
        createdAt: Long,
        sortOrder: Long = 0,
        priority: Int = 0,
    ) = Download(
        id = id,
        url = "https://example.com/$id.bin",
        fileName = "$id.bin",
        priority = priority,
        sortOrder = sortOrder,
        createdAtEpochMillis = createdAt,
    )

    private fun queuedIds(): List<String> =
        repository!!.downloads.value.filter { it.state == DownloadState.QUEUED }.map { it.id }

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
            if (version >= 5) DownloadDatabase.migrateFourToFive(db)
            if (version >= 6) DownloadDatabase.migrateFiveToSix(db)
            if (version >= 7) DownloadDatabase.migrateSixToSeven(db)
            if (version >= 8) DownloadDatabase.migrateSevenToEight(db)
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

    private fun assertReferenceSha256ColumnExists() {
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
                assertEquals(true, names.contains("reference_sha256"))
            }
        }
    }

    private fun assertAutomaticRetryCountColumnExists() {
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
                assertEquals(true, names.contains("automatic_retry_count"))
            }
        }
    }

    private fun assertPauseCauseColumnExists() {
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
                assertEquals(true, names.contains("pause_cause"))
            }
        }
    }

    private fun assertDestinationColumnsExist() {
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
                assertEquals(true, names.contains("destination_tree_uri"))
                assertEquals(true, names.contains("destination_display_label"))
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
