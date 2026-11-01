package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.*
import com.espitman.sdm.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebQueueGroupDeviceTest {
    @Test fun groupTopPreservesSelectionOrderPriorityProgressAndHeldStates() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val production=AppRepositories.downloads(context);production.awaitInitialized()
        val original=production.downloads.value
        val name="web-queue-${UUID.randomUUID()}.db"
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val repository=SqliteDownloadRepository(context,scope,name)
        try {
            repository.awaitInitialized()
            val records=(0..5).map { n -> Download(id="fixture-$n",url="https://example.invalid/$n",fileName="$n.bin",state=if(n==5)DownloadState.COMPLETED else DownloadState.PAUSED,priority=if(n==4)1 else 0,sortOrder=n.toLong(),downloadedBytes=if(n==5)20 else (n+1).toLong(),totalBytes=20,createdAtEpochMillis=n.toLong(),completedAtEpochMillis=if(n==5)6 else null) }
            records.forEach { repository.insert(it) }
            assertTrue(WebQueueCommands.moveGroupToTop(repository,linkedSetOf("fixture-3","fixture-1","fixture-4","fixture-5"),10))
            val ordered=repository.downloads.value.filter { it.state!=DownloadState.COMPLETED }.sortedWith(DownloadQueueOrder.comparator)
            assertEquals(listOf("fixture-4","fixture-1","fixture-3","fixture-0","fixture-2"),ordered.map { it.id })
            records.forEach { before ->
                val after=checkNotNull(repository.get(before.id))
                assertEquals(before.state,after.state);assertEquals(before.downloadedBytes,after.downloadedBytes);assertEquals(before.priority,after.priority)
            }
            assertEquals(records.last(),repository.get("fixture-5"))
            assertFalse(WebQueueCommands.moveGroupToTop(repository,setOf("fixture-1","fixture-3"),20))
            assertFalse(WebQueueCommands.moveGroupToTop(repository,setOf("missing","fixture-5"),30))
            assertEquals(original,production.downloads.value)
        } finally { repository.close();scope.cancel();context.deleteDatabase(name) }
    }
    @Test fun externalChromeGroupTopFixture() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        val tier=(originals.maxOfOrNull {it.priority}?:0)+1
        val now=System.currentTimeMillis()
        val records=(1..3).map {n->Download(id=UUID.randomUUID().toString(),url="https://example.invalid/owned-queue-$n.bin",fileName="SDM-queue-browser-$n.bin",state=DownloadState.PAUSED,priority=tier,sortOrder=n.toLong(),downloadedBytes=n*10L,totalBytes=100,createdAtEpochMillis=now)}
        WebPanelRuntime.initialize(context)
        context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            records.forEach {repo.insert(it)}
            println("BROWSER_QUEUE_FIXTURE_READY")
            withTimeout(600_000){while(repo.downloads.value.filter {it.id in records.map {r->r.id}}.sortedWith(DownloadQueueOrder.comparator).map {it.id}!=listOf(records[1].id,records[2].id,records[0].id))delay(100)}
            records.forEach {before->val after=repo.get(before.id)!!;assertEquals(before.state,after.state);assertEquals(before.downloadedBytes,after.downloadedBytes);assertEquals(before.priority,after.priority)}
            assertEquals(originals,repo.downloads.value.filter {it.id !in records.map {r->r.id}})
            println("BROWSER_QUEUE_FIXTURE_VERIFIED")
            delay(45_000)
        } finally {records.forEach {repo.delete(it.id)}}
        assertEquals(originals,repo.downloads.value)
    }
    @Test fun externalChromeGroupDragFixture() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        val tier=(originals.maxOfOrNull {it.priority}?:0)+1
        val now=System.currentTimeMillis()
        val records=(1..3).map {n->Download(id=UUID.randomUUID().toString(),url="https://example.invalid/owned-queue-$n.bin",fileName="SDM-queue-drag-$n.bin",state=DownloadState.PAUSED,priority=tier,sortOrder=n.toLong(),downloadedBytes=n*10L,totalBytes=100,createdAtEpochMillis=now)}
        WebPanelRuntime.initialize(context)
        context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            records.forEach {repo.insert(it)}
            println("BROWSER_QUEUE_FIXTURE_READY")
            withTimeout(120_000){while(repo.downloads.value.filter {it.id in records.map {r->r.id}}.sortedWith(DownloadQueueOrder.comparator).map {it.id}!=listOf(records[1].id,records[2].id,records[0].id))delay(100)}
            records.forEach {before->val after=repo.get(before.id)!!;assertEquals(before.state,after.state);assertEquals(before.downloadedBytes,after.downloadedBytes);assertEquals(before.priority,after.priority)}
            assertEquals(originals,repo.downloads.value.filter {it.id !in records.map {r->r.id}})
            println("BROWSER_QUEUE_FIXTURE_VERIFIED")
            delay(15_000)

        } finally {records.forEach {repo.delete(it.id)}}
        assertEquals(originals,repo.downloads.value)
    }

}
