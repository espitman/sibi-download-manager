package com.espitman.sdm.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadCancelMutation
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadMoveToTopMutation
import com.espitman.sdm.domain.DownloadPriorityMutation
import com.espitman.sdm.domain.DownloadRenameMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.PauseQueuedMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.ZoneId

class SqliteDownloadRepository(
    context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    databaseName: String = DownloadDatabase.DATABASE_NAME,
    private val localZoneId: ZoneId = ZoneId.systemDefault(),
) : DownloadRepository, AutoCloseable {
    private val database = DownloadDatabase(context, databaseName)
    private val mutex = Mutex()
    private val mutableDownloads = MutableStateFlow<List<Download>>(emptyList())
    private val initialized = CompletableDeferred<Unit>()

    override val downloads: StateFlow<List<Download>> = mutableDownloads.asStateFlow()

    init {
        scope.launch {
            runCatching { mutex.withLock { refreshLocked(database.readableDatabase) } }
                .onSuccess { initialized.complete(Unit) }
                .onFailure { initialized.completeExceptionally(it) }
        }
    }

    override suspend fun awaitInitialized() = initialized.await()

    override suspend fun get(id: String): Download? = onIo {
        awaitInitialized()
        mutex.withLock { queryOne(database.readableDatabase, id) }
    }

    override suspend fun schedulingSnapshot(): List<Download> = onIo {
        awaitInitialized()
        mutex.withLock { downloads.value }
    }

    override suspend fun insert(download: Download) = onIo {
        awaitInitialized()
        mutex.withLock {
            check(queryOne(database.readableDatabase, download.id) == null) {
                "Download ${download.id} already exists; use an atomic repository operation to modify it"
            }
            database.writableDatabase.inTransaction { db ->
                db.insertOrThrow(
                    "downloads",
                    null,
                    download.toValues(),
                ).also { check(it != -1L) { "Unable to persist download ${download.id}" } }
            }
            refreshLocked(database.readableDatabase)
        }
    }

    override suspend fun delete(id: String): Boolean = onIo {
        awaitInitialized()
        mutex.withLock {
            val deleted = database.writableDatabase.inTransaction { db ->
                db.delete("downloads", "id = ?", arrayOf(id)) > 0
            }
            if (deleted) refreshLocked(database.readableDatabase)
            deleted
        }
    }

    override suspend fun transition(
        id: String,
        to: DownloadState,
        nowEpochMillis: Long,
        error: String?,
    ): Download = mutate(id) { current ->
        DownloadStateMachine.transition(current, to, nowEpochMillis, error)
    }

    override suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        nowEpochMillis: Long,
    ): Download = mutate(id) { current ->
        require(current.state == DownloadState.DOWNLOADING) {
            "Progress can only change while downloading"
        }
        require(downloadedBytes >= current.downloadedBytes) { "Download progress cannot move backwards" }
        require(nowEpochMillis >= current.updatedAtEpochMillis) { "Progress time cannot move backwards" }
        current.copy(downloadedBytes = downloadedBytes, updatedAtEpochMillis = nowEpochMillis)
    }

    override suspend fun pauseAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download? = onIo {
        awaitInitialized()
        mutex.withLock {
            var updated: Download? = null
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: return@inTransaction
                val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis)
                if (paused === current || paused == current) {
                    updated = current
                    return@inTransaction
                }
                persistDownloadMutation(db, id, current, paused)
                updated = paused
            }
            if (updated != null && updated!!.state == DownloadState.PAUSED) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun cancelAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download? = onIo {
        awaitInitialized()
        mutex.withLock {
            var updated: Download? = null
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: return@inTransaction
                val cancelled = DownloadCancelMutation.apply(current, fileLengthBytes, nowEpochMillis)
                if (cancelled === current || cancelled == current) {
                    updated = current
                    return@inTransaction
                }
                persistDownloadMutation(db, id, current, cancelled)
                updated = cancelled
            }
            if (updated != null && updated!!.state == DownloadState.CANCELLED) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun resumePaused(
        id: String,
        nowEpochMillis: Long,
    ): Download? = onIo {
        awaitInitialized()
        mutex.withLock {
            var updated: Download? = null
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: return@inTransaction
                val queued = DownloadResumeMutation.apply(current, nowEpochMillis) ?: return@inTransaction
                persistDownloadMutation(db, id, current, queued)
                updated = queued
            }
            if (updated != null && updated!!.state == DownloadState.QUEUED) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun requeueForDownloadAll(nowEpochMillis: Long): List<Download> = onIo {
        awaitInitialized()
        mutex.withLock {
            val updated = ArrayList<Download>()
            database.writableDatabase.inTransaction { db ->
                for (current in downloads.value) {
                    val next = DownloadAllMutation.apply(current, nowEpochMillis) ?: continue
                    persistDownloadMutation(db, current.id, current, next)
                    updated += next
                }
            }
            if (updated.isNotEmpty()) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun pauseQueuedPreservingOffsets(nowEpochMillis: Long): List<Download> = onIo {
        awaitInitialized()
        mutex.withLock {
            val updated = ArrayList<Download>()
            database.writableDatabase.inTransaction { db ->
                for (current in downloads.value) {
                    val next = PauseQueuedMutation.apply(current, nowEpochMillis) ?: continue
                    persistDownloadMutation(db, current.id, current, next)
                    updated += next
                }
            }
            if (updated.isNotEmpty()) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun togglePriority(
        id: String,
        nowEpochMillis: Long,
    ): Download? = onIo {
        awaitInitialized()
        mutex.withLock {
            var updated: Download? = null
            var changed = false
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: return@inTransaction
                val next = DownloadPriorityMutation.toggle(current, nowEpochMillis)
                if (next === current || next == current) {
                    updated = current
                    return@inTransaction
                }
                persistDownloadMutation(db, id, current, next)
                updated = next
                changed = true
            }
            if (changed) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun moveToTop(
        id: String,
        nowEpochMillis: Long,
    ): Download? = onIo {
        awaitInitialized()
        mutex.withLock {
            var updated: Download? = null
            var changed = false
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: return@inTransaction
                val peers = queryQueuedSamePriority(db, current.priority)
                val nextRecords = DownloadMoveToTopMutation.apply(current, peers, nowEpochMillis)
                if (nextRecords.isEmpty()) {
                    updated = current
                    return@inTransaction
                }
                val previousById = peers.associateBy { it.id } + (current.id to current)
                for (next in nextRecords) {
                    val previous = previousById.getValue(next.id)
                    check(previous.priority == next.priority)
                    check(previous.downloadedBytes == next.downloadedBytes)
                    check(previous.state == next.state)
                    persistIdentity(db, next.id, next)
                }
                updated = nextRecords.first { it.id == id }
                changed = true
            }
            if (changed) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun renameRecord(
        id: String,
        fileName: String,
        destinationPath: String,
        nowEpochMillis: Long,
    ): Download = onIo {
        awaitInitialized()
        mutex.withLock {
            lateinit var updated: Download
            var changed = false
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: error("Download $id does not exist")
                updated = DownloadRenameMutation.apply(current, fileName, destinationPath, nowEpochMillis)
                check(current.downloadedBytes == updated.downloadedBytes)
                if (updated != current) {
                    persistIdentity(db, id, updated)
                    changed = true
                }
            }
            if (changed) {
                refreshLocked(database.readableDatabase)
            }
            updated
        }
    }

    override suspend fun beginFreshRestart(
        id: String,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download = mutate(id) { current ->
        DownloadFreshRestartMutation.apply(
            current = current,
            nowEpochMillis = nowEpochMillis,
            etag = etag,
            lastModified = lastModified,
            totalBytes = totalBytes,
        )
    }

    private suspend fun mutate(id: String, update: (Download) -> Download): Download = onIo {
        awaitInitialized()
        mutex.withLock {
            lateinit var updated: Download
            database.writableDatabase.inTransaction { db ->
                val current = queryOne(db, id) ?: error("Download $id does not exist")
                updated = update(current)
                persistDownloadMutation(db, id, current, updated)
            }
            refreshLocked(database.readableDatabase)
            updated
        }
    }

    override suspend fun transferredBytesForLocalDay(
        nowEpochMillis: Long,
        zoneId: ZoneId,
    ): Long = onIo {
        awaitInitialized()
        mutex.withLock {
            queryDailyTotal(database.readableDatabase, DailyTransferAccounting.dayKey(nowEpochMillis, zoneId))
        }
    }

    private fun persistDownloadMutation(
        db: SQLiteDatabase,
        id: String,
        current: Download,
        next: Download,
    ) {
        check(db.update("downloads", next.toValues(), "id = ?", arrayOf(id)) == 1) {
            "Concurrent update failed for download $id"
        }
        addDailyTransferLocked(db, current.downloadedBytes, next.downloadedBytes, next.updatedAtEpochMillis)
    }

    private fun persistIdentity(db: SQLiteDatabase, id: String, next: Download) {
        check(db.update("downloads", next.toValues(), "id = ?", arrayOf(id)) == 1) {
            "Concurrent update failed for download $id"
        }
    }

    private fun addDailyTransferLocked(
        db: SQLiteDatabase,
        previousBytes: Long,
        nextBytes: Long,
        atEpochMillis: Long,
    ) {
        val delta = DailyTransferAccounting.positiveDelta(previousBytes, nextBytes)
        if (delta == 0L) return
        val dayKey = DailyTransferAccounting.dayKey(atEpochMillis, localZoneId)
        val total = DailyTransferAccounting.saturatingAdd(queryDailyTotal(db, dayKey), delta)
        val values = ContentValues().apply {
            put("day_key", dayKey)
            put("transferred_bytes", total)
        }
        if (db.update("daily_transfer_totals", values, "day_key = ?", arrayOf(dayKey)) == 0) {
            check(db.insertOrThrow("daily_transfer_totals", null, values) != -1L) {
                "Unable to persist daily transfer total for $dayKey"
            }
        }
    }

    private fun queryDailyTotal(db: SQLiteDatabase, dayKey: String): Long =
        db.query(
            "daily_transfer_totals",
            arrayOf("transferred_bytes"),
            "day_key = ?",
            arrayOf(dayKey),
            null,
            null,
            null,
            "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else 0L }

    private fun refreshLocked(db: SQLiteDatabase) {
        mutableDownloads.value = db.query(
            "downloads",
            COLUMNS,
            null,
            null,
            null,
            null,
            "priority DESC, sort_order ASC, created_at ASC, id ASC",
        ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toDownload()) } }
    }

    private fun queryQueuedSamePriority(db: SQLiteDatabase, priority: Int): List<Download> = db.query(
        "downloads",
        COLUMNS,
        "state = ? AND priority = ?",
        arrayOf(DownloadState.QUEUED.name, priority.toString()),
        null,
        null,
        null,
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.toDownload()) } }

    private fun queryOne(db: SQLiteDatabase, id: String): Download? = db.query(
        "downloads",
        COLUMNS,
        "id = ?",
        arrayOf(id),
        null,
        null,
        null,
        "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toDownload() else null }

    override fun close() = database.close()

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(Dispatchers.IO) { block() }

    private companion object {
        val COLUMNS = arrayOf(
            "id", "url", "file_name", "mime_type", "etag", "last_modified", "destination_path", "total_bytes",
            "downloaded_bytes", "state", "error", "priority", "sort_order", "created_at", "updated_at",
            "started_at", "completed_at", "accepts_ranges",
        )
    }
}

private inline fun <T> SQLiteDatabase.inTransaction(block: (SQLiteDatabase) -> T): T {
    beginTransaction()
    return try {
        block(this).also { setTransactionSuccessful() }
    } finally {
        endTransaction()
    }
}

private fun Download.toValues() = ContentValues().apply {
    put("id", id)
    put("url", url)
    put("file_name", fileName)
    putNullable("mime_type", mimeType)
    putNullable("etag", etag)
    putNullable("last_modified", lastModified)
    putNullable("destination_path", destinationPath)
    putNullable("total_bytes", totalBytes)
    put("downloaded_bytes", downloadedBytes)
    put("state", state.name)
    putNullable("error", error)
    put("priority", priority)
    put("sort_order", sortOrder)
    put("created_at", createdAtEpochMillis)
    put("updated_at", updatedAtEpochMillis)
    putNullable("started_at", startedAtEpochMillis)
    putNullable("completed_at", completedAtEpochMillis)
    putNullable("accepts_ranges", acceptsRanges)
}

private fun ContentValues.putNullable(key: String, value: String?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Long?) {
    if (value == null) putNull(key) else put(key, value)
}

private fun ContentValues.putNullable(key: String, value: Boolean?) {
    if (value == null) putNull(key) else put(key, if (value) 1 else 0)
}

private fun Cursor.toDownload() = Download(
    id = getString(getColumnIndexOrThrow("id")),
    url = getString(getColumnIndexOrThrow("url")),
    fileName = getString(getColumnIndexOrThrow("file_name")),
    mimeType = nullableString("mime_type"),
    etag = nullableString("etag"),
    lastModified = nullableString("last_modified"),
    destinationPath = nullableString("destination_path"),
    totalBytes = nullableLong("total_bytes"),
    downloadedBytes = getLong(getColumnIndexOrThrow("downloaded_bytes")),
    state = DownloadState.valueOf(getString(getColumnIndexOrThrow("state"))),
    error = nullableString("error"),
    priority = getInt(getColumnIndexOrThrow("priority")),
    sortOrder = getLong(getColumnIndexOrThrow("sort_order")),
    createdAtEpochMillis = getLong(getColumnIndexOrThrow("created_at")),
    updatedAtEpochMillis = getLong(getColumnIndexOrThrow("updated_at")),
    startedAtEpochMillis = nullableLong("started_at"),
    completedAtEpochMillis = nullableLong("completed_at"),
    acceptsRanges = nullableBoolean("accepts_ranges"),
)

private fun Cursor.nullableString(column: String): String? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getString(it) }

private fun Cursor.nullableLong(column: String): Long? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getLong(it) }

private fun Cursor.nullableBoolean(column: String): Boolean? =
    getColumnIndexOrThrow(column).let { if (isNull(it)) null else getInt(it) != 0 }
