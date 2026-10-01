package com.espitman.sdm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Requires the deterministic range fixture at adb-reversed localhost:18765. Uses isolated data only. */
@RunWith(AndroidJUnit4::class)
class IndividualSpeedLimitLiveDeviceTest {
    @Test fun concurrentSegmentsLiveChangesPauseResumeAndUnlimited() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "live-speed-${UUID.randomUUID()}.db"
        val directory = File(context.cacheDir, name).apply { mkdirs() }
        val repository = SqliteDownloadRepository(context, databaseName = name)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val globalRate = AtomicReference<Long?>(250_000)
        val global = AggregateSpeedLimiter({ globalRate.get() })
        val aBytes = AtomicLong(); val bBytes = AtomicLong()
        val pause = AtomicBoolean(false)
        var aJob: Job? = null; var bJob: Job? = null
        try {
            repository.awaitInitialized()
            fun record(id: String, limit: Long?) = Download(id = id, url = "http://127.0.0.1:18765/$id", fileName = "$id.bin",
                destinationPath = File(directory, "$id.bin").path, totalBytes = 8_388_608, acceptsRanges = true,
                etag = "\"sdm-rate-fixture\"", speedLimitBytesPerSecond = limit, createdAtEpochMillis = System.currentTimeMillis())
            val a = record("a",100_000); val b = record("b",200_000)
            repository.insert(a); repository.insert(b)
            val individual = IndividualSpeedLimiters(repository.downloads, scope)
            fun start(record: Download, counter: AtomicLong): Job = scope.launch {
                DownloadTransferEngine(speedLimiter = global, individualSpeedLimiter = individual::forDownload,
                    segmentCount = { 2 }, onChunkRead = { counter.addAndGet(it.toLong()) })
                    .executeTransfer(record.id, record.url, DownloadPartFile.forDestination(File(record.destinationPath!!)), repository, pauseRequested = pause::get)
            }
            suspend fun window(label: String, aCap: Long?, bCap: Long?, globalCap: Long?): Pair<Double,Double> {
                delay(500)
                val start = System.nanoTime(); val aa=aBytes.get(); val bb=bBytes.get()
                delay(4000)
                val seconds=(System.nanoTime()-start)/1e9
                val ar=(aBytes.get()-aa)/seconds; val br=(bBytes.get()-bb)/seconds
                println("$label: A=$ar B=$br combined=${ar+br} bytes/s over $seconds seconds")
                // Include a single bucket's allowed burst and scheduling tolerance in each observation window.
                if(aCap!=null) assertTrue("$label A=$ar cap=$aCap",ar<=aCap*1.15+20_000)
                if(bCap!=null) assertTrue("$label B=$br cap=$bCap",br<=bCap*1.15+20_000)
                if(globalCap!=null) assertTrue("$label combined=${ar+br}",ar+br<=globalCap*1.15+20_000)
                assertTrue("$label A stalled",ar>15_000); assertTrue("$label B stalled",br>15_000)
                return ar to br
            }
            aJob=start(a,aBytes); bJob=start(b,bBytes)
            window("segmented concurrent",100_000,200_000,250_000)
            repository.updateSpeedLimit(a.id,40_000,System.currentTimeMillis())
            window("live individual reduction",40_000,200_000,250_000)
            pause.set(true)
            aJob.cancelAndJoin(); bJob.cancelAndJoin()
            assertEquals(DownloadState.PAUSED,repository.get(a.id)?.state)
            assertEquals(DownloadState.PAUSED,repository.get(b.id)?.state)
            val paused=aBytes.get() to bBytes.get(); delay(500)
            assertEquals(paused,aBytes.get() to bBytes.get())
            assertEquals(40_000L,repository.get(a.id)?.speedLimitBytesPerSecond)
            repository.resumePaused(a.id,System.currentTimeMillis()); repository.resumePaused(b.id,System.currentTimeMillis())
            pause.set(false)
            aJob=start(repository.get(a.id)!!,aBytes); bJob=start(repository.get(b.id)!!,bBytes)
            window("resumed",40_000,200_000,250_000)
            repository.updateSpeedLimit(a.id,null,System.currentTimeMillis()); repository.updateSpeedLimit(b.id,null,System.currentTimeMillis())
            globalRate.set(120_000); global.notifyPolicyChanged()
            window("individual unlimited global capped",null,null,120_000)
            globalRate.set(null); global.notifyPolicyChanged()
            withTimeout(20_000) { aJob.join(); bJob.join() }
            for(record in listOf(a,b)) {
                assertEquals(DownloadState.COMPLETED,repository.get(record.id)?.state)
                val bytes=File(record.destinationPath!!).readBytes()
                assertEquals(8_388_608,bytes.size)
                assertTrue("corrupt resumed file ${record.id}",bytes.withIndex().all { (i,v)->v==i.toByte() })
            }
        } finally {
            scope.cancel(); aJob?.cancelAndJoin(); bJob?.cancelAndJoin()
            repository.close(); context.deleteDatabase(name); directory.deleteRecursively()
        }
    }
}
