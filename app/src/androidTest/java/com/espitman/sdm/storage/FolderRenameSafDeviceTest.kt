package com.espitman.sdm.storage

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.*
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Two explicit phases allow the real Android permission picker between rename and recovery. */
@RunWith(AndroidJUnit4::class)
class FolderRenameSafDeviceTest {
    private val base=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(base.cacheDir,"sdm-saf-rename-device-qa").apply {mkdirs()}
    private val context=object:ContextWrapper(base) {
        override fun getApplicationContext():Context=this
        override fun getSharedPreferences(name:String,mode:Int)=base.getSharedPreferences("rename-saf-qa-$name",mode)
        override fun getExternalFilesDir(type:String?):File=root
        override fun getFilesDir():File=root
    }
    @Test fun renameAndRecoverRealExternalStorageFolder() = runBlocking {
        val args=InstrumentationRegistry.getArguments()
        val phase=requireNotNull(args.getString("phase"))
        val tree=requireNotNull(args.getString("qaTreeUri"))
        val repo=SqliteDownloadRepository(base,databaseName="sdm-saf-rename-device-qa.db")
        val store=SaveLocationStore(context.getSharedPreferences("sdm_settings",0))
        val categories=CategoryFolderStore(context.getSharedPreferences("sdm_category_folders",0))
        val scheduler=DownloadQueueScheduler(repo,{1},{})
        val transferred=java.util.concurrent.atomic.AtomicLong()
        val pause=AtomicBoolean(false);var worker:Job?=null
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        val coordinator=FolderRenameCoordinator(context,repo,scheduler,store,categories,{pause.set(true);worker?.cancel()},{worker?.isCompleted==false})
        val trees=DocumentsContractTreeAccess(base.contentResolver)
        val grants=PersistableTreeUriGrants(base.contentResolver)
        try {
            repo.awaitInitialized()
            assertTrue(grants.hasReadWrite(tree))
            if(phase=="rename") {
                assertTrue(repo.downloads.value.isEmpty())
                store.persistUserTree(tree,"SDM-Rename-QA")
                categories.replace(CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to CategoryFolderRule(tree,"SDM-Rename-QA"))))
                val old=trees.createFile(tree,"text/plain","existing.txt")
                val source=File(root,"source.txt").apply {writeText("saf keep")};trees.writeFrom(old.documentUri,source)
                val local=SaveLocationDestinationAllocator.stagingDirectory(AppSpecificDownloadsDirectory.from(context))
                val d=Download(id="active-saf",url="http://127.0.0.1:18765/active-saf",fileName="active.bin",destinationPath=File(local,"active.bin").path,
                    destinationTreeUri=tree,destinationDisplayLabel="SDM-Rename-QA",totalBytes=8_388_608,acceptsRanges=true,etag="\"sdm-rate-fixture\"",createdAtEpochMillis=1,sortOrder=4)
                repo.insert(d)
                repo.insert(d.copy(id="queued-saf",url="http://127.0.0.1:18765/queued-saf",fileName="queued.bin",destinationPath=File(local,"queued.bin").path,sortOrder=6))
                repo.insert(d.copy(id="completed-saf",url="http://127.0.0.1:18765/completed-saf",fileName="existing.txt",destinationPath=old.documentUri,totalBytes=8,downloadedBytes=8,state=DownloadState.COMPLETED,completedAtEpochMillis=2))
                worker=scope.launch {DownloadTransferEngine(onChunkRead={transferred.addAndGet(it.toLong())},speedLimiter=AggregateSpeedLimiter({100_000L}),segmentCount={2},preserveSegmentsOnPause={FolderRenameCoordinator.isPending(context)})
                    .executeTransfer(d.id,d.url,DownloadPartFile.forDestination(File(d.destinationPath!!)),repo,pauseRequested=pause::get)}
                withTimeout(15_000) {while((repo.get(d.id)?.downloadedBytes ?: 0)<100_000) delay(100)}
                assertFalse(coordinator.rename("SDM-Rename-QA-Renamed"))
                worker!!.join()
                assertTrue(FolderRenameCoordinator.isPending(context));assertTrue(scheduler.folderMutationBlocked)
                assertEquals(DownloadState.PAUSED,repo.get(d.id)!!.state);assertTrue(repo.get(d.id)!!.downloadedBytes>0)
                assertEquals("Every segment byte must survive renaming",transferred.get(),repo.get(d.id)!!.downloadedBytes)
                assertFalse(coordinator.recover()) // a new process still cannot silently use revoked access
                println("SAF RENAME WAITING FOR GRANT: ${coordinator.pendingTree()}; retained ${repo.get(d.id)!!.downloadedBytes} bytes")
            } else {
                assertEquals("finish",phase)
                assertTrue(coordinator.recover(tree))
                assertFalse(FolderRenameCoordinator.isPending(context))
                val active=repo.get("active-saf")!!;val queued=repo.get("queued-saf")!!;val completed=repo.get("completed-saf")!!
                assertEquals(tree,active.destinationTreeUri);assertEquals(tree,queued.destinationTreeUri)
                assertEquals(4L,active.sortOrder);assertEquals(6L,queued.sortOrder)
                assertEquals(DownloadState.QUEUED,active.state);assertEquals(DownloadState.QUEUED,queued.state)
                assertEquals(tree,categories.settings.value.rules[FileCategory.VIDEO]!!.treeUri)
                assertEquals(tree,store.read().treeUri)
                assertEquals("saf keep",base.contentResolver.openInputStream(Uri.parse(completed.destinationPath))!!.bufferedReader().use {it.readText()})
                val coordinatorForPublish=SaveLocationCoordinator(store,grants,trees)
                DownloadTransferEngine(segmentCount={2},destinationPublisher=SafDownloadDestinationPublisher(trees,coordinatorForPublish,{AppSpecificDownloadsDirectory.from(context)}))
                    .executeTransfer(active.id,active.url,DownloadPartFile.forDestination(File(active.destinationPath!!)),repo)
                val done=repo.get(active.id)!!;assertEquals(DownloadState.COMPLETED,done.state);assertEquals(tree,done.destinationTreeUri)
                val bytes=base.contentResolver.openInputStream(Uri.parse(done.destinationPath))!!.use {it.readBytes()}
                assertEquals(8_388_608,bytes.size);assertTrue(bytes.withIndex().all {(i,v)->v==i.toByte()})
                println("SAF COMPLETE PASSED: existing file readable, category/default/queued URI updated, active resumed byte-for-byte")
            }
        } finally {
            worker?.cancelAndJoin();scope.cancel();repo.close()
            if(phase=="finish") {
                base.deleteDatabase("sdm-saf-rename-device-qa.db");root.deleteRecursively()
                listOf("sdm_settings","sdm_category_folders","sdm_folder_migration").forEach {context.getSharedPreferences(it,0).edit().clear().commit()}
            }
            FolderRenameCoordinator.isPending(base)
        }
    }
}
