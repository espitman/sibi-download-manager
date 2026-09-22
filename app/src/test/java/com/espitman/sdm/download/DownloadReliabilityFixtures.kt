package com.espitman.sdm.download

import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadAllMutation
import com.espitman.sdm.domain.DownloadCancelMutation
import com.espitman.sdm.domain.DownloadFreshRestartMutation
import com.espitman.sdm.domain.DownloadPauseMutation
import com.espitman.sdm.domain.DownloadPriorityMutation
import com.espitman.sdm.domain.DownloadResumeMutation
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadStateMachine
import com.espitman.sdm.domain.PauseQueuedMutation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

internal class AdjustableClock(initial: Long = 1_000L) : Clock {
    private val now = AtomicLong(initial)
    override fun currentTimeMillis(): Long = now.get()
    fun advance(millis: Long) {
        now.addAndGet(millis)
    }
}

internal class ContractDownloadRepository(
    initial: List<Download> = emptyList(),
) : DownloadRepository {
    private val mutex = Mutex()
    private val _downloads = MutableStateFlow(initial)
    override val downloads: StateFlow<List<Download>> = _downloads.asStateFlow()
    val insertAttempts = AtomicInteger(0)
    val invalidTransitions = CopyOnWriteArrayList<Throwable>()

    override suspend fun awaitInitialized() {}

    override suspend fun get(id: String): Download? = mutex.withLock {
        _downloads.value.find { it.id == id }
    }

    override suspend fun schedulingSnapshot(): List<Download> = mutex.withLock {
        _downloads.value.toList()
    }

    override suspend fun insert(download: Download) = mutex.withLock {
        check(_downloads.value.none { it.id == download.id }) {
            "Download ${download.id} already exists; use an atomic repository operation to modify it"
        }
        replaceLocked(download)
        insertAttempts.incrementAndGet()
        Unit
    }

    override suspend fun delete(id: String): Boolean = mutex.withLock {
        val existed = _downloads.value.any { it.id == id }
        _downloads.value = _downloads.value.filterNot { it.id == id }
        existed
    }

    override suspend fun transition(
        id: String,
        to: DownloadState,
        nowEpochMillis: Long,
        error: String?,
    ): Download = mutex.withLock {
        val current = requireRecordLocked(id)
        try {
            val updated = DownloadStateMachine.transition(current, to, nowEpochMillis, error)
            replaceLocked(updated)
            updated
        } catch (thrown: Throwable) {
            invalidTransitions += thrown
            throw thrown
        }
    }

    override suspend fun updateProgress(
        id: String,
        downloadedBytes: Long,
        nowEpochMillis: Long,
    ): Download = mutex.withLock {
        val current = requireRecordLocked(id)
        require(current.state == DownloadState.DOWNLOADING) {
            "Progress can only change while downloading"
        }
        require(downloadedBytes >= current.downloadedBytes) { "Download progress cannot move backwards" }
        require(nowEpochMillis >= current.updatedAtEpochMillis) { "Progress time cannot move backwards" }
        current.totalBytes?.let { require(downloadedBytes <= it) { "Download progress cannot exceed total" } }
        val updated = current.copy(downloadedBytes = downloadedBytes, updatedAtEpochMillis = nowEpochMillis)
        replaceLocked(updated)
        updated
    }

    override suspend fun pauseAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download? = pauseAtExactOffset(id, fileLengthBytes, nowEpochMillis, pauseCause = null)

    override suspend fun pauseAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
        pauseCause: com.espitman.sdm.domain.DownloadPauseCause?,
    ): Download? = mutex.withLock {
        val current = _downloads.value.find { it.id == id } ?: return@withLock null
        val paused = DownloadPauseMutation.apply(current, fileLengthBytes, nowEpochMillis, pauseCause)
        if (paused != current) replaceLocked(paused)
        paused
    }

    override suspend fun cancelAtExactOffset(
        id: String,
        fileLengthBytes: Long,
        nowEpochMillis: Long,
    ): Download? = mutex.withLock {
        val current = _downloads.value.find { it.id == id } ?: return@withLock null
        val cancelled = DownloadCancelMutation.apply(current, fileLengthBytes, nowEpochMillis)
        if (cancelled != current) replaceLocked(cancelled)
        cancelled
    }

    override suspend fun resumePaused(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
        val current = _downloads.value.find { it.id == id } ?: return@withLock null
        val queued = DownloadResumeMutation.apply(current, nowEpochMillis) ?: return@withLock null
        replaceLocked(queued)
        queued
    }

    override suspend fun requeueForDownloadAll(nowEpochMillis: Long): List<Download> = mutex.withLock {
        val updated = ArrayList<Download>()
        for (download in _downloads.value) {
            val next = DownloadAllMutation.apply(download, nowEpochMillis) ?: continue
            replaceLocked(next)
            updated += next
        }
        updated
    }

    override suspend fun pauseQueuedPreservingOffsets(nowEpochMillis: Long): List<Download> =
        pauseQueuedPreservingOffsets(nowEpochMillis, pauseCause = null)

    override suspend fun pauseQueuedPreservingOffsets(
        nowEpochMillis: Long,
        pauseCause: com.espitman.sdm.domain.DownloadPauseCause?,
    ): List<Download> = mutex.withLock {
        val updated = ArrayList<Download>()
        for (download in _downloads.value) {
            val next = PauseQueuedMutation.apply(download, nowEpochMillis, pauseCause) ?: continue
            replaceLocked(next)
            updated += next
        }
        updated
    }

    override suspend fun togglePriority(id: String, nowEpochMillis: Long): Download? = mutex.withLock {
        val current = _downloads.value.find { it.id == id } ?: return@withLock null
        val updated = DownloadPriorityMutation.toggle(current, nowEpochMillis)
        if (updated != current) replaceLocked(updated)
        updated
    }

    override suspend fun beginFreshRestart(
        id: String,
        nowEpochMillis: Long,
        etag: String?,
        lastModified: String?,
        totalBytes: Long?,
    ): Download = mutex.withLock {
        val current = requireRecordLocked(id)
        val updated = DownloadFreshRestartMutation.apply(current, nowEpochMillis, etag, lastModified, totalBytes)
        replaceLocked(updated)
        updated
    }

    suspend fun awaitState(id: String, state: DownloadState): Download =
        downloads.first { list -> list.any { it.id == id && it.state == state } }.first { it.id == id }

    private fun requireRecordLocked(id: String): Download =
        _downloads.value.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Download not found: $id")

    private fun replaceLocked(download: Download) {
        _downloads.value = _downloads.value.filterNot { it.id == download.id } + download
    }
}

internal data class ObservedHttpRequest(
    val path: String,
    val rangeStart: Long?,
    val ifRange: String?,
)

internal class RangePayloadDispatcher(
    private val payloads: Map<String, ByteArray>,
    private val etag: String,
    private val lastModified: String,
) : Dispatcher() {
    val requests = CopyOnWriteArrayList<ObservedHttpRequest>()
    val rangeStartsByPath = ConcurrentHashMap<String, CopyOnWriteArrayList<Long>>()
    private val dispatching = AtomicInteger(0)
    val peakConcurrentDispatches = AtomicInteger(0)

    override fun dispatch(request: RecordedRequest): MockResponse {
        peakConcurrentDispatches.updateAndGet { current -> max(current, dispatching.incrementAndGet()) }
        try {
            return dispatchLocked(request)
        } finally {
            dispatching.decrementAndGet()
        }
    }

    private fun dispatchLocked(request: RecordedRequest): MockResponse {
        val path = request.path?.substringBefore('?').orEmpty()
        val payload = payloads[path]
            ?: return MockResponse().setResponseCode(404).setBody("missing $path")
        val rangeHeader = request.getHeader(HttpRangeResume.HEADER_RANGE)
        val rangeStart = parseRangeStart(rangeHeader)
        requests += ObservedHttpRequest(
            path = path,
            rangeStart = rangeStart,
            ifRange = request.getHeader(HttpRangeResume.HEADER_IF_RANGE),
        )
        if (rangeStart != null) {
            rangeStartsByPath.getOrPut(path) { CopyOnWriteArrayList() }.add(rangeStart)
        }
        val start = rangeStart ?: 0L
        if (start < 0L || start > payload.size) {
            return MockResponse().setResponseCode(HttpRangeResume.HTTP_RANGE_NOT_SATISFIABLE)
        }
        val slice = payload.copyOfRange(start.toInt(), payload.size)
        val body = Buffer().write(slice)
        val response = MockResponse()
            .setHeader(HttpRangeResume.HEADER_ETAG, etag)
            .setHeader(HttpRangeResume.HEADER_LAST_MODIFIED, lastModified)
            .setBody(body)
        return if (rangeStart == null) {
            response.setResponseCode(HttpRangeResume.HTTP_OK)
        } else {
            val end = payload.size - 1
            response
                .setResponseCode(HttpRangeResume.HTTP_PARTIAL_CONTENT)
                .setHeader(HttpRangeResume.HEADER_CONTENT_RANGE, "bytes $start-$end/${payload.size}")
        }
    }

    private fun parseRangeStart(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val match = Regex("""bytes=(\d+)-""").matchEntire(header.trim())
            ?: throw IllegalArgumentException("Unexpected Range header: $header")
        return match.groupValues[1].toLong()
    }
}

internal class ConcurrentCallCounter : EventListener() {
    private val inFlight = AtomicInteger(0)
    private val live = ConcurrentHashMap.newKeySet<Call>()
    val peak = AtomicInteger(0)

    override fun responseBodyStart(call: Call) = enter(call)

    override fun responseBodyEnd(call: Call, byteCount: Long) = exit(call)

    override fun callFailed(call: Call, ioe: IOException) = exit(call)

    override fun canceled(call: Call) = exit(call)

    private fun enter(call: Call) {
        if (!live.add(call)) return
        peak.updateAndGet { current -> max(current, inFlight.incrementAndGet()) }
    }

    private fun exit(call: Call) {
        if (!live.remove(call)) return
        inFlight.decrementAndGet()
    }
}

internal class ChunkPauseGate {
    private val pauseAt = AtomicLong(-1L)
    private val written = AtomicLong(0L)
    @Volatile private var parked: CountDownLatch? = null
    @Volatile private var hold: CountDownLatch? = null

    private val signalled = java.util.concurrent.atomic.AtomicBoolean(false)

    fun arm(absoluteBytes: Long, alreadyWritten: Long = 0L) {
        written.set(alreadyWritten)
        pauseAt.set(absoluteBytes)
        signalled.set(false)
        parked = CountDownLatch(1)
        hold = CountDownLatch(1)
    }

    fun disarm() {
        pauseAt.set(-1L)
        hold?.countDown()
        parked = null
        hold = null
        written.set(0L)
        signalled.set(false)
    }

    fun onChunk(bytes: Int) {
        val after = written.addAndGet(bytes.toLong())
        val target = pauseAt.get()
        if (target >= 0L && after >= target && signalled.compareAndSet(false, true)) {
            parked?.countDown()
            check(hold?.await(15, TimeUnit.SECONDS) == true) {
                "Pause hold timed out at target=$target written=$after"
            }
        }
    }

    fun awaitParked(detail: () -> String = { "" }) {
        val latch = parked ?: error("Pause gate is not armed")
        check(latch.await(15, TimeUnit.SECONDS)) {
            "Timed out waiting for pause barrier written=${written.get()} target=${pauseAt.get()} ${detail()}"
        }
    }

    fun release() {
        hold?.countDown()
    }

    fun written(): Long = written.get()
}

internal class JvmDownloadTransferHost(
    val repository: ContractDownloadRepository,
    private val concurrentLimit: () -> Int,
    private val clock: Clock,
    private val engineFor: (downloadId: String) -> DownloadTransferEngine,
) : QueuedTransferStarter {
    val session = DownloadTransferSession()
    val scheduler = DownloadQueueScheduler(repository, concurrentLimit, this, clock)
    val startRequestedIds = CopyOnWriteArrayList<String>()
    val startedIds = CopyOnWriteArrayList<String>()
    val overlappingById = ConcurrentHashMap<String, AtomicInteger>()
    val peakPerId = ConcurrentHashMap<String, AtomicInteger>()
    val peakActiveTransfers = AtomicInteger(0)
    val transferFailures = CopyOnWriteArrayList<Throwable>()
    private val activeTransfers = AtomicInteger(0)
    private val startIds = AtomicInteger(0)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val finished = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.IO)

    override fun startQueued(download: Download) {
        val destination = download.destinationPath
            ?: throw IllegalStateException("Queued download ${download.id} has no destination")
        startRequestedIds += download.id
        dispatch(
            StartTransferCommand(
                downloadId = download.id,
                tempFilePath = DownloadPartFile.forDestination(File(destination)).absolutePath,
            ),
        )
    }

    fun dispatch(command: TransferCommand) {
        val startId = startIds.incrementAndGet()
        if (command is ResumeTransferCommand) {
            session.handleCommand(startId, command = null)
            scope.launch {
                scheduler.resume(command.downloadId)
            }
            return
        }
        when (val result = session.handleCommand(startId, command)) {
            is SessionCommandResult.StartJob -> {
                val downloadId = result.command.downloadId
                val done = CompletableDeferred<Unit>()
                finished[downloadId] = done
                val transferJob = scope.launch {
                    val current = overlappingById.getOrPut(downloadId) { AtomicInteger(0) }.incrementAndGet()
                    peakPerId.getOrPut(downloadId) { AtomicInteger(0) }.updateAndGet { max(it, current) }
                    peakActiveTransfers.updateAndGet { max(it, activeTransfers.incrementAndGet()) }
                    startedIds += downloadId
                    try {
                        when (val transferCommand = result.command) {
                            is StartTransferCommand -> {
                                val download = repository.get(transferCommand.downloadId) ?: return@launch
                                engineFor(transferCommand.downloadId).executeTransfer(
                                    downloadId = download.id,
                                    url = download.url,
                                    tempFile = File(transferCommand.tempFilePath),
                                    repository = repository,
                                    pauseRequested = { session.isPauseRequested(transferCommand.downloadId) },
                                )
                            }
                            else -> Unit
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (thrown: Throwable) {
                        transferFailures += thrown
                    } finally {
                        overlappingById[downloadId]?.decrementAndGet()
                        activeTransfers.decrementAndGet()
                        withContext(NonCancellable) {
                            persistCancelledIfRequested(downloadId)
                            TransferSlotCleanup.afterTransferFinished(
                                acceptingWork = true,
                                releaseClaim = { scheduler.releaseClaim(downloadId) },
                                reschedule = { scheduler.schedule() },
                            )
                            session.onTransferFinished(downloadId)
                            done.complete(Unit)
                        }
                    }
                }
                jobs[downloadId] = transferJob
                if (session.attachJob(downloadId, transferJob)) {
                    transferJob.cancel()
                }
            }
            is SessionCommandResult.CancelJob -> result.job.cancel()
            SessionCommandResult.None -> {
                if (command is CancelTransferCommand && !session.isCancelRequested(command.downloadId)) {
                    scope.launch {
                        withContext(NonCancellable) {
                            persistCancelledNow(command.downloadId)
                            scheduler.releaseClaim(command.downloadId)
                        }
                        scheduler.schedule()
                    }
                }
            }
        }
    }

    suspend fun awaitFinished(downloadId: String) {
        finished[downloadId]?.await()
    }

    fun killActive(downloadId: String) {
        jobs[downloadId]?.cancel()
    }

    fun close() {
        job.cancel()
        scope.cancel()
    }

    private suspend fun persistCancelledIfRequested(downloadId: String) {
        if (!session.isCancelRequested(downloadId)) return
        persistCancelledNow(downloadId)
    }

    private suspend fun persistCancelledNow(downloadId: String) {
        val download = repository.get(downloadId) ?: return
        val path = session.tempFilePath(downloadId)
            ?: download.destinationPath?.let { DownloadPartFile.forDestination(File(it)).absolutePath }
        val length = path?.let { File(it) }?.let { file ->
            if (file.exists()) file.length().coerceAtLeast(0L) else 0L
        } ?: 0L
        repository.cancelAtExactOffset(
            id = download.id,
            fileLengthBytes = length,
            nowEpochMillis = max(clock.currentTimeMillis(), download.updatedAtEpochMillis),
        )
    }
}

internal fun reliabilityPayload(size: Int, salt: Int = 0): ByteArray =
    ByteArray(size) { index -> ((index + salt) % 251).toByte() }

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun sha256Hex(bytes: ByteArray): String =
    sha256(bytes).joinToString("") { "%02x".format(it) }

internal fun queuedDownload(
    id: String,
    url: String,
    destinationPath: String,
    totalBytes: Long,
    etag: String,
    lastModified: String,
    priority: Int = 0,
    createdAt: Long = 1_000L,
) = Download(
    id = id,
    url = url,
    fileName = "$id.bin",
    etag = etag,
    lastModified = lastModified,
    destinationPath = destinationPath,
    totalBytes = totalBytes,
    state = DownloadState.QUEUED,
    priority = priority,
    createdAtEpochMillis = createdAt,
    updatedAtEpochMillis = createdAt,
)

internal fun reliabilityClient(listener: EventListener): OkHttpClient =
    OkHttpClient.Builder()
        .eventListener(listener)
        .retryOnConnectionFailure(false)
        .addNetworkInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("Accept-Encoding", "identity")
                    .build(),
            )
        }
        .build()
