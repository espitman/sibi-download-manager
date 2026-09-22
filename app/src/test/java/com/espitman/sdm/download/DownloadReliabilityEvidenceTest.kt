package com.espitman.sdm.download

import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

class DownloadReliabilityEvidenceTest {

    private lateinit var server: MockWebServer
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        server = MockWebServer()
        tempDir = File(System.getProperty("java.io.tmpdir"), "sdm_reliability_${System.nanoTime()}")
        tempDir.mkdirs()
    }

    @After
    fun tearDown() {
        server.shutdown()
        tempDir.deleteRecursively()
    }

    @Test
    fun repeatedPauseResumeFiveCyclesCompletesOneRecordWithMatchingChecksum() = runBlocking {
        val payload = reliabilityPayload(PAUSE_PAYLOAD_BYTES)
        val expectedDigest = sha256(payload)
        val etag = "\"reliability-v1\""
        val lastModified = "Wed, 21 Oct 2015 07:28:00 GMT"
        val dispatcher = RangePayloadDispatcher(
            payloads = mapOf("/pause.bin" to payload),
            etag = etag,
            lastModified = lastModified,
        )
        server.dispatcher = dispatcher
        server.start()

        val downloadId = "pause-resume-one"
        val dest = File(tempDir, "$downloadId.bin")
        val part = DownloadPartFile.forDestination(dest)
        val restart = DownloadPartFile.restartForDestination(dest)
        val clock = AdjustableClock()
        val repo = ContractDownloadRepository()
        repo.insert(
            queuedDownload(
                id = downloadId,
                url = server.url("/pause.bin").toString(),
                destinationPath = dest.absolutePath,
                totalBytes = payload.size.toLong(),
                etag = etag,
                lastModified = lastModified,
            ),
        )
        val gate = ChunkPauseGate()
        val calls = ConcurrentCallCounter()
        val host = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = {
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(calls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES.toLong(),
                    onChunkRead = gate::onChunk,
                )
            },
        )
        try {
            val observedOffsets = ArrayList<Long>()
            repeat(PAUSE_CYCLES) { cycle ->
                val already = if (part.exists()) part.length() else 0L
                val offset = already + 4L * CHUNK_BYTES
                gate.arm(offset, alreadyWritten = already)
                if (cycle == 0) {
                    host.scheduler.schedule()
                } else {
                    host.dispatch(ResumeTransferCommand(downloadId))
                }
                withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.DOWNLOADING) }
                gate.awaitParked {
                    "state=${repo.downloads.value} failures=${host.transferFailures} starts=${host.startedIds}"
                }
                assertEquals(gate.written(), part.length())
                assertTrue(
                    "Paused at ${part.length()} before planned barrier $offset",
                    part.length() >= offset,
                )
                host.dispatch(PauseTransferCommand(downloadId))
                gate.release()
                withTimeout(15_000) {
                    repo.awaitState(downloadId, DownloadState.PAUSED)
                    host.awaitFinished(downloadId)
                }
                val paused = repo.get(downloadId)!!
                assertEquals(1, repo.downloads.value.size)
                assertEquals(downloadId, paused.id)
                assertEquals(part.length(), paused.downloadedBytes)
                assertTrue(paused.downloadedBytes <= payload.size)
                assertTrue(observedOffsets.isEmpty() || paused.downloadedBytes > observedOffsets.last())
                observedOffsets += paused.downloadedBytes
                assertArrayEquals(payload.copyOf(paused.downloadedBytes.toInt()), part.readBytes())
                assertFalse(dest.exists())
                assertFalse(restart.exists())
                clock.advance(10)
            }
            gate.disarm()
            host.dispatch(ResumeTransferCommand(downloadId))
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.COMPLETED) }

            val completed = repo.get(downloadId)!!
            assertEquals(1, repo.downloads.value.filter { it.id == downloadId }.size)
            assertEquals(DownloadState.COMPLETED, completed.state)
            assertEquals(payload.size.toLong(), completed.downloadedBytes)
            assertEquals(payload.size.toLong(), completed.totalBytes)
            assertEquals(payload.size.toLong(), dest.length())
            assertArrayEquals(expectedDigest, sha256(dest.readBytes()))
            assertTrue(completed.completedAtEpochMillis != null)
            assertTrue(completed.updatedAtEpochMillis >= completed.createdAtEpochMillis)
            assertEquals(completed.updatedAtEpochMillis, completed.completedAtEpochMillis)
            assertFalse(part.exists())
            assertFalse(restart.exists())
            assertTrue(repo.invalidTransitions.isEmpty())
            assertEquals(1, repo.insertAttempts.get())
            try {
                repo.insert(completed)
                throw AssertionError("duplicate insert must fail")
            } catch (thrown: IllegalStateException) {
                assertTrue(thrown.message!!.contains("already exists"))
            }

            val rangeStarts = dispatcher.rangeStartsByPath["/pause.bin"].orEmpty()
            // The last pause can land after the whole response body was written.
            // In that case Resume finalizes the complete part without another GET.
            val expectedRangeStarts = if (observedOffsets.last() == payload.size.toLong()) {
                observedOffsets.dropLast(1)
            } else {
                observedOffsets
            }
            assertEquals(expectedRangeStarts, rangeStarts)
            assertEquals(expectedRangeStarts.size + 1, dispatcher.requests.size)
            assertNull(dispatcher.requests.first().rangeStart)
            for (index in 1 until dispatcher.requests.size) {
                assertEquals(expectedRangeStarts[index - 1], dispatcher.requests[index].rangeStart)
                assertEquals(etag, dispatcher.requests[index].ifRange)
            }
            assertEquals(PAUSE_CYCLES, observedOffsets.size)
            assertEquals(1, host.peakPerId[downloadId]!!.get())
        } finally {
            gate.disarm()
            host.close()
        }
    }

    @Test
    fun rapidDuplicateCommandsKeepOneJobAndCancelWinsPauseRace() = runBlocking {
        val payload = reliabilityPayload(64 * 1024, salt = 7)
        val etag = "\"race-v1\""
        val lastModified = "Thu, 22 Oct 2015 07:28:00 GMT"
        val dispatcher = RangePayloadDispatcher(
            payloads = mapOf("/race.bin" to payload),
            etag = etag,
            lastModified = lastModified,
        )
        server.dispatcher = dispatcher
        server.start()

        val downloadId = "rapid-commands"
        val dest = File(tempDir, "$downloadId.bin")
        val part = DownloadPartFile.forDestination(dest)
        val clock = AdjustableClock()
        val repo = ContractDownloadRepository(
            listOf(
                queuedDownload(
                    id = downloadId,
                    url = server.url("/race.bin").toString(),
                    destinationPath = dest.absolutePath,
                    totalBytes = payload.size.toLong(),
                    etag = etag,
                    lastModified = lastModified,
                ),
            ),
        )
        val gate = ChunkPauseGate()
        val calls = ConcurrentCallCounter()
        val host = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = {
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(calls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES.toLong(),
                    onChunkRead = gate::onChunk,
                )
            },
        )
        try {
            gate.arm(CHUNK_BYTES * 4L)
            host.scheduler.schedule()
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.DOWNLOADING) }
            gate.awaitParked {
                "state=${repo.downloads.value} failures=${host.transferFailures} starts=${host.startedIds}"
            }

            val go = kotlinx.coroutines.CompletableDeferred<Unit>()
            val ready = AtomicInteger(0)
            val allReady = kotlinx.coroutines.CompletableDeferred<Unit>()
            val startCommand = StartTransferCommand(downloadId, part.absolutePath)
            val commands = buildList {
                repeat(12) { add(ResumeTransferCommand(downloadId)) }
                repeat(12) { add(startCommand) }
            }
            coroutineScope {
                val workers = commands.map { command ->
                    async(Dispatchers.Default) {
                        if (ready.incrementAndGet() == commands.size) allReady.complete(Unit)
                        go.await()
                        host.dispatch(command)
                        host.scheduler.schedule()
                    }
                }
                allReady.await()
                go.complete(Unit)
                workers.awaitAll()
            }
            assertEquals(1, host.peakPerId[downloadId]!!.get())
            assertEquals(1, host.startedIds.count { it == downloadId })
            assertEquals(1, repo.downloads.value.size)
            assertEquals(DownloadState.DOWNLOADING, repo.get(downloadId)!!.state)

            val pauseGo = kotlinx.coroutines.CompletableDeferred<Unit>()
            val cancelGo = kotlinx.coroutines.CompletableDeferred<Unit>()
            val bothReady = kotlinx.coroutines.CompletableDeferred<Unit>()
            val started = AtomicInteger(0)
            coroutineScope {
                val pauseWorker = async(Dispatchers.Default) {
                    if (started.incrementAndGet() == 2) bothReady.complete(Unit)
                    pauseGo.await()
                    host.dispatch(PauseTransferCommand(downloadId))
                }
                val cancelWorker = async(Dispatchers.Default) {
                    if (started.incrementAndGet() == 2) bothReady.complete(Unit)
                    cancelGo.await()
                    host.dispatch(CancelTransferCommand(downloadId))
                }
                bothReady.await()
                pauseGo.complete(Unit)
                cancelGo.complete(Unit)
                awaitAll(pauseWorker, cancelWorker)
            }
            gate.release()
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.CANCELLED) }
            val terminal = repo.get(downloadId)!!
            assertEquals(DownloadState.CANCELLED, terminal.state)
            assertEquals(1, repo.downloads.value.size)
            assertEquals(downloadId, terminal.id)
            assertNotEquals(DownloadState.COMPLETED, terminal.state)
            assertNull(terminal.completedAtEpochMillis)
            assertEquals(1, host.peakPerId[downloadId]!!.get())
            assertTrue(
                "Duplicate transfer starts for one id: ${host.startedIds}",
                host.startedIds.count { it == downloadId } == 1,
            )
            assertTrue(repo.invalidTransitions.isEmpty())
            assertTrue(host.transferFailures.isEmpty())
            assertFalse(dest.exists())
            host.dispatch(PauseTransferCommand(downloadId))
            host.dispatch(CancelTransferCommand(downloadId))
            host.dispatch(CancelTransferCommand(downloadId))
            assertEquals(DownloadState.CANCELLED, repo.get(downloadId)!!.state)
            assertEquals(1, repo.downloads.value.size)
        } finally {
            gate.disarm()
            host.close()
        }
        assertTrue(!part.exists() || part.length() <= payload.size)
    }

    @Test
    fun concurrentTransfersHonorLimitPriorityAndIsolatedChecksums() = runBlocking {
        val etag = "\"concurrent-v1\""
        val lastModified = "Fri, 23 Oct 2015 07:28:00 GMT"
        val specs = listOf(
            Triple("low", 0, 40L),
            Triple("mid-early", 2, 10L),
            Triple("mid-late", 2, 20L),
            Triple("high-late", 4, 30L),
        )
        val payloads = specs.associate { (id, _, _) ->
            "/$id.bin" to reliabilityPayload(24 * 1024, salt = id.hashCode())
        }
        val dispatcher = RangePayloadDispatcher(payloads, etag, lastModified)
        server.dispatcher = dispatcher
        server.start()

        val clock = AdjustableClock()
        val repo = ContractDownloadRepository()
        for ((id, priority, createdAt) in specs) {
            repo.insert(
                queuedDownload(
                    id = id,
                    url = server.url("/$id.bin").toString(),
                    destinationPath = File(tempDir, "$id.bin").absolutePath,
                    totalBytes = payloads["/$id.bin"]!!.size.toLong(),
                    etag = etag,
                    lastModified = lastModified,
                    priority = priority,
                    createdAt = createdAt,
                ),
            )
        }
        val warmup = kotlinx.coroutines.CompletableDeferred<Unit>()
        val firstHits = AtomicInteger(0)
        val twoStarted = kotlinx.coroutines.CompletableDeferred<Unit>()
        val calls = ConcurrentCallCounter()
        val host = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = { _ ->
                val seenFirst = java.util.concurrent.atomic.AtomicBoolean(false)
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(calls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES.toLong(),
                    onChunkRead = {
                        if (seenFirst.compareAndSet(false, true) && !warmup.isCompleted) {
                            if (firstHits.incrementAndGet() >= 2) twoStarted.complete(Unit)
                            runBlocking { warmup.await() }
                        }
                    },
                )
            },
        )
        try {
            host.scheduler.schedule()
            withTimeout(15_000) { twoStarted.await() }
            assertEquals(listOf("high-late", "mid-early"), host.startRequestedIds.toList())
            assertEquals(2, host.peakActiveTransfers.get())
            assertEquals(2, repo.downloads.value.count { it.state == DownloadState.DOWNLOADING })
            assertEquals(DownloadState.QUEUED, repo.get("mid-late")!!.state)
            assertEquals(DownloadState.QUEUED, repo.get("low")!!.state)
            warmup.complete(Unit)
            withTimeout(20_000) {
                repo.downloads.first { list -> list.all { it.state == DownloadState.COMPLETED } }
            }
            assertEquals(listOf("high-late", "mid-early", "mid-late", "low"), host.startRequestedIds.toList())
            assertEquals(2, host.peakActiveTransfers.get())
            assertEquals(4, dispatcher.requests.size)
            assertEquals(4, dispatcher.requests.map { it.path }.toSet().size)
            for ((id, _, _) in specs) {
                val dest = File(tempDir, "$id.bin")
                val source = payloads["/$id.bin"]!!
                assertArrayEquals(sha256(source), sha256(dest.readBytes()))
                assertEquals(source.size.toLong(), dest.length())
                assertFalse(DownloadPartFile.forDestination(dest).exists())
                assertFalse(DownloadPartFile.restartForDestination(dest).exists())
                assertEquals(1, host.peakPerId[id]!!.get())
            }
            assertEquals(4, repo.downloads.value.map { it.id }.toSet().size)
            assertEquals(4, repo.insertAttempts.get())
            val destBytes = specs.associate { (id, _, _) -> id to File(tempDir, "$id.bin").readBytes() }
            assertFalse(destBytes["low"]!!.contentEquals(destBytes["high-late"]!!))
            assertTrue(host.transferFailures.isEmpty())
            assertTrue(repo.invalidTransitions.isEmpty())
        } finally {
            if (!warmup.isCompleted) warmup.complete(Unit)
            host.close()
        }
    }

    @Test
    fun processDeathRecoveryDoesNotCompleteAndResumeMatchesChecksum() = runBlocking {
        val payload = reliabilityPayload(48 * 1024, salt = 19)
        val expectedDigest = sha256(payload)
        val etag = "\"recovery-v1\""
        val lastModified = "Sat, 24 Oct 2015 07:28:00 GMT"
        val dispatcher = RangePayloadDispatcher(
            payloads = mapOf("/recovery.bin" to payload),
            etag = etag,
            lastModified = lastModified,
        )
        server.dispatcher = dispatcher
        server.start()

        val downloadId = "process-death"
        val dest = File(tempDir, "$downloadId.bin")
        val part = DownloadPartFile.forDestination(dest)
        val restart = DownloadPartFile.restartForDestination(dest)
        val clock = AdjustableClock()
        val repo = ContractDownloadRepository(
            listOf(
                queuedDownload(
                    id = downloadId,
                    url = server.url("/recovery.bin").toString(),
                    destinationPath = dest.absolutePath,
                    totalBytes = payload.size.toLong(),
                    etag = etag,
                    lastModified = lastModified,
                ),
            ),
        )
        val interruptAt = CHUNK_BYTES * 6L
        val gate = ChunkPauseGate()
        val calls = ConcurrentCallCounter()
        val dyingHost = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = {
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(calls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES * 8L,
                    onChunkRead = gate::onChunk,
                )
            },
        )
        try {
            gate.arm(interruptAt)
            dyingHost.scheduler.schedule()
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.DOWNLOADING) }
            gate.awaitParked {
                "state=${repo.downloads.value} failures=${dyingHost.transferFailures} starts=${dyingHost.startedIds}"
            }
            val partialLength = part.length()
            assertTrue(partialLength >= interruptAt)
            assertTrue(partialLength < payload.size)
            assertTrue(part.exists())
            assertFalse(dest.exists())
            dyingHost.killActive(downloadId)
            gate.release()
            withTimeout(15_000) { dyingHost.awaitFinished(downloadId) }
        } finally {
            dyingHost.close()
        }

        val preservedLength = part.length()
        assertTrue(preservedLength >= interruptAt)
        assertTrue(preservedLength < payload.size)

        val interrupted = repo.get(downloadId)!!
        assertEquals(downloadId, interrupted.id)
        assertEquals(1, repo.downloads.value.size)
        assertTrue(interrupted.state == DownloadState.DOWNLOADING || interrupted.state == DownloadState.CONNECTING)
        assertNotEquals(DownloadState.COMPLETED, interrupted.state)
        assertNull(interrupted.completedAtEpochMillis)
        assertTrue(part.exists())
        assertEquals(preservedLength, part.length())

        val recoveryGate = DownloadRecoveryOnceGate()
        val recoveries = AtomicInteger(0)
        coroutineScope {
            val callers = List(6) {
                async(Dispatchers.Default) {
                    recoveryGate.runOnce {
                        recoveries.incrementAndGet()
                        DownloadInterruptionRecovery.recover(
                            repository = repo,
                            clock = clock,
                            trigger = DownloadInterruptionTrigger.PROCESS_RESTART,
                        )
                    }
                }
            }
            callers.awaitAll()
        }
        assertEquals(1, recoveries.get())
        val failed = repo.get(downloadId)!!
        assertEquals(DownloadState.FAILED, failed.state)
        assertEquals(DownloadInterruptionTrigger.PROCESS_RESTART.errorMessage, failed.error)
        assertEquals(1, repo.downloads.value.size)
        assertNotEquals(DownloadState.COMPLETED, failed.state)
        assertNull(failed.completedAtEpochMillis)
        assertTrue(part.exists())
        assertEquals(preservedLength, part.length())
        assertTrue(failed.downloadedBytes <= part.length())

        val revivedCalls = ConcurrentCallCounter()
        val revivedHost = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = {
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(revivedCalls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES.toLong(),
                )
            },
        )
        try {
            revivedHost.scheduler.schedule()
            assertEquals(DownloadState.FAILED, repo.get(downloadId)!!.state)
            assertTrue(revivedHost.startRequestedIds.isEmpty())

            revivedHost.scheduler.downloadAll()
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.COMPLETED) }

            val completed = repo.get(downloadId)!!
            assertEquals(1, repo.downloads.value.size)
            assertEquals(downloadId, completed.id)
            assertEquals(DownloadState.COMPLETED, completed.state)
            assertEquals(payload.size.toLong(), completed.downloadedBytes)
            assertArrayEquals(expectedDigest, sha256(dest.readBytes()))
            assertFalse(part.exists())
            assertFalse(restart.exists())
            val resumeStarts = dispatcher.rangeStartsByPath["/recovery.bin"].orEmpty()
            assertEquals(listOf(preservedLength), resumeStarts.toList())
            assertEquals(etag, dispatcher.requests.last { it.rangeStart != null }.ifRange)
            assertEquals(1, revivedHost.peakPerId[downloadId]!!.get())
            assertTrue(repo.invalidTransitions.isEmpty())
        } finally {
            revivedHost.close()
        }
    }

    @Test
    fun deviceBootRecoveryResumesPreservedPartialRangeAndMatchesChecksum() = runBlocking {
        val payload = reliabilityPayload(48 * 1024, salt = 23)
        val expectedDigest = sha256(payload)
        val etag = "\"boot-recovery-v1\""
        val lastModified = "Sun, 25 Oct 2015 07:28:00 GMT"
        val dispatcher = RangePayloadDispatcher(
            payloads = mapOf("/boot-recovery.bin" to payload),
            etag = etag,
            lastModified = lastModified,
        )
        server.dispatcher = dispatcher
        server.start()

        val downloadId = "device-boot-recovery"
        val destination = File(tempDir, "$downloadId.bin")
        val part = DownloadPartFile.forDestination(destination)
        val restart = DownloadPartFile.restartForDestination(destination)
        val partialLength = CHUNK_BYTES * 3L
        part.writeBytes(payload.copyOf(partialLength.toInt()))
        val interrupted = queuedDownload(
            id = downloadId,
            url = server.url("/boot-recovery.bin").toString(),
            destinationPath = destination.absolutePath,
            totalBytes = payload.size.toLong(),
            etag = etag,
            lastModified = lastModified,
        ).copy(
            state = DownloadState.DOWNLOADING,
            downloadedBytes = partialLength,
            updatedAtEpochMillis = 2_000L,
        )
        val clock = AdjustableClock(5_000L)
        val repo = ContractDownloadRepository(listOf(interrupted))

        DownloadInterruptionRecovery.recover(
            repository = repo,
            clock = clock,
            trigger = DownloadInterruptionTrigger.DEVICE_BOOT,
            autoResume = true,
        )

        val recovered = repo.get(downloadId)!!
        assertEquals(DownloadState.QUEUED, recovered.state)
        assertEquals(partialLength, recovered.downloadedBytes)
        assertNull(recovered.error)
        assertTrue(part.exists())
        assertEquals(partialLength, part.length())

        val calls = ConcurrentCallCounter()
        val host = JvmDownloadTransferHost(
            repository = repo,
            concurrentLimit = { 2 },
            clock = clock,
            engineFor = {
                DownloadTransferEngine(
                    okHttpClient = reliabilityClient(calls),
                    ioDispatcher = Dispatchers.IO,
                    clock = clock,
                    bufferSizeBytes = CHUNK_BYTES,
                    progressUpdateIntervalBytes = CHUNK_BYTES.toLong(),
                )
            },
        )
        try {
            host.scheduler.schedule()
            withTimeout(15_000) { repo.awaitState(downloadId, DownloadState.COMPLETED) }

            val completed = repo.get(downloadId)!!
            assertEquals(listOf(downloadId), host.startRequestedIds.toList())
            assertTrue(host.transferFailures.isEmpty())
            assertEquals(DownloadState.COMPLETED, completed.state)
            assertEquals(payload.size.toLong(), completed.downloadedBytes)
            assertArrayEquals(expectedDigest, sha256(destination.readBytes()))
            assertEquals(listOf(partialLength), dispatcher.rangeStartsByPath["/boot-recovery.bin"]!!.toList())
            assertEquals(etag, dispatcher.requests.single().ifRange)
            assertFalse(part.exists())
            assertFalse(restart.exists())
        } finally {
            host.close()
        }
    }

    companion object {
        private const val CHUNK_BYTES = 4 * 1024
        private const val PAUSE_CYCLES = 5
        private const val PAUSE_PAYLOAD_BYTES = 6 * 4 * CHUNK_BYTES
    }
}
