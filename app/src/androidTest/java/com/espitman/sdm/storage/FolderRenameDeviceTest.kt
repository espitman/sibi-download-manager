package com.espitman.sdm.storage

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class FolderRenameDeviceTest {
    private class IsolatedContext(base: Context, val root: File, val prefix: String) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) = baseContext.getSharedPreferences("$prefix-$name", mode)
        override fun getExternalFilesDir(type: String?): File = root
        override fun getFilesDir(): File = root
    }
    @Test fun realActiveRangeTransferQueuedAndCompletedFilesSurviveDefaultFolderRename() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val prefix = "rename-${UUID.randomUUID()}"
        val root = File(base.cacheDir, prefix).apply { mkdirs() }
        val context = IsolatedContext(base, root, prefix)
        val repo = SqliteDownloadRepository(base, databaseName = "$prefix.db")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val transferred = java.util.concurrent.atomic.AtomicLong()
        val pause = AtomicBoolean(false); var worker: Job? = null
        val launches = mutableListOf<String>()
        val scheduler = DownloadQueueScheduler(repo, { 1 }, { synchronized(launches) { launches += it.id } })
        val store = SaveLocationStore(context.getSharedPreferences("sdm_settings", 0))
        val categories = CategoryFolderStore(context.getSharedPreferences("sdm_category_folders", 0))
        val coordinator = FolderRenameCoordinator(context, repo, scheduler, store, categories,
            pauseActive = { pause.set(true); worker?.cancel() }, workersAlive = { worker?.isCompleted == false })
        try {
            repo.awaitInitialized()
            val dir = AppSpecificDownloadsDirectory.from(context)
            val active = Download(id="active",url="http://127.0.0.1:18765/active",fileName="active.bin",destinationPath=File(dir,"active.bin").path,
                totalBytes=8_388_608,acceptsRanges=true,etag="\"sdm-rate-fixture\"",sortOrder=7,createdAtEpochMillis=1)
            val queued = active.copy(id="queued",url="http://127.0.0.1:18765/queued",fileName="queued.bin",destinationPath=File(dir,"queued.bin").path,sortOrder=9)
            val manual = queued.copy(id="manual",url="http://127.0.0.1:18765/manual",state=DownloadState.PAUSED)
            val completed = active.copy(id="completed",url="http://127.0.0.1:18765/completed",fileName="existing.txt",destinationPath=File(dir,"existing.txt").path,
                state=DownloadState.COMPLETED,totalBytes=4,downloadedBytes=4,completedAtEpochMillis=2)
            File(completed.destinationPath!!).writeText("keep")
            File(dir,"untracked.txt").writeText("also keep")
            DownloadPartFile.forDestination(File(queued.destinationPath!!)).writeBytes(byteArrayOf())
            listOf(active, queued, manual, completed).forEach { repo.insert(it) }
            val limiter = AggregateSpeedLimiter({100_000L})
            worker = scope.launch {
                DownloadTransferEngine(onChunkRead={transferred.addAndGet(it.toLong())},speedLimiter=limiter,segmentCount={2},preserveSegmentsOnPause={FolderRenameCoordinator.isPending(context)}).executeTransfer(active.id,active.url,
                    DownloadPartFile.forDestination(File(active.destinationPath!!)),repo,pauseRequested=pause::get)
            }
            withTimeout(15_000) { while ((repo.get(active.id)?.downloadedBytes ?: 0) < 120_000) delay(100) }
            assertTrue(coordinator.rename("Renamed downloads"))
            worker!!.join()
            val next = repo.get(active.id)!!
            assertEquals(DownloadState.QUEUED,next.state)
            assertTrue(next.downloadedBytes>0)
            assertEquals("Every segment byte must survive renaming",transferred.get(),next.downloadedBytes)
            assertEquals(7L,next.sortOrder)
            val newDir = AppSpecificDownloadsDirectory.from(context)
            assertEquals("Renamed downloads",newDir.name)
            assertEquals("keep",File(newDir,"existing.txt").readText())
            assertEquals("also keep",File(newDir,"untracked.txt").readText())
            assertEquals(DownloadState.QUEUED,repo.get(queued.id)!!.state)
            assertEquals(DownloadState.PAUSED,repo.get(manual.id)!!.state)
            assertEquals(9L,repo.get(queued.id)!!.sortOrder)
            assertFalse(FolderRenameCoordinator.isPending(context))
            pause.set(false)
            DownloadTransferEngine(segmentCount={2},preserveSegmentsOnPause={FolderRenameCoordinator.isPending(context)}).executeTransfer(next.id,next.url,DownloadPartFile.forDestination(File(next.destinationPath!!)),repo)
            assertEquals(DownloadState.COMPLETED,repo.get(next.id)!!.state)
            val bytes=File(next.destinationPath!!).readBytes()
            assertEquals(8_388_608,bytes.size)
            assertTrue(bytes.withIndex().all { (i,v)->v==i.toByte() })
            println("RENAME ACTIVE PASSED: retained ${next.downloadedBytes} bytes; segmented transfer resumed and finished byte-for-byte")
            File(newDir.parentFile,"Collision").mkdir()
            try { coordinator.rename("Collision"); fail("Collision accepted") } catch (_: IllegalStateException) { }
            assertEquals(newDir,AppSpecificDownloadsDirectory.from(context))
            assertEquals("keep",File(newDir,"existing.txt").readText())
        } finally {
            worker?.cancelAndJoin(); scope.cancel(); repo.close(); base.deleteDatabase("$prefix.db");root.deleteRecursively()
            listOf("sdm_settings","sdm_category_folders","sdm_folder_migration").forEach { context.getSharedPreferences(it,0).edit().clear().commit() }
            FolderRenameCoordinator.isPending(context)
        }
    }
    @Test fun deniedTreeAccessLeavesTheExistingRecordsAndFolderConfigurationUntouched() = runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>();val prefix="denied-${UUID.randomUUID()}"
        val root=File(base.cacheDir,prefix).apply {mkdirs()};val context=IsolatedContext(base,root,prefix)
        val repo=SqliteDownloadRepository(base,databaseName="$prefix.db")
        val store=SaveLocationStore(context.getSharedPreferences("sdm_settings",0));val categories=CategoryFolderStore(context.getSharedPreferences("sdm_category_folders",0))
        val scheduler=DownloadQueueScheduler(repo,{1},{fail("Must not start downloads on denied access")})
        val tree="content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSDM-Rename-No-Grant"
        try {
            repo.awaitInitialized();store.persistUserTree(tree,"No grant")
            val d=Download(url="http://127.0.0.1:18765/denied",fileName="denied.bin",downloadedBytes=12,destinationPath=File(root,"denied.bin").path,destinationTreeUri=tree,createdAtEpochMillis=1,state=DownloadState.PAUSED)
            repo.insert(d)
            try {FolderRenameCoordinator(context,repo,scheduler,store,categories).rename("New folder");fail("Denied access accepted")}
            catch (_: Exception) { }
            assertEquals(d,repo.get(d.id));assertEquals(tree,store.read().treeUri)
            assertFalse(FolderRenameCoordinator.isPending(context));assertFalse(scheduler.folderMutationBlocked)
        } finally {
            repo.close();base.deleteDatabase("$prefix.db");root.deleteRecursively()
            listOf("sdm_settings","sdm_category_folders","sdm_folder_migration").forEach {context.getSharedPreferences(it,0).edit().clear().commit()};FolderRenameCoordinator.isPending(context)
        }
    }
    @Test fun externalDirectoryCollisionMetadataIsVisibleWithScopedStorage() {
        val path=androidx.test.platform.app.InstrumentationRegistry.getArguments().getString("qaCollisionPath")
        org.junit.Assume.assumeTrue("Requires an isolated collision fixture created through adb", path != null)
        assertTrue(File(requireNotNull(path)).isDirectory)
    }
    @Test fun documentUrisMapOnlyTheExactFolderAndItsDescendants() {
        val old="content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSDM"
        val new="content://com.android.externalstorage.documents/tree/primary%3ADownload%2FRenamed"
        val source=android.provider.DocumentsContract.buildDocumentUriUsingTree(Uri.parse(old),"primary:Download/SDM/sub/existing.mp4").toString()
        val mapped=FolderRenameCoordinator.remapUri(source,old,new)
        assertEquals("primary:Download/Renamed/sub/existing.mp4",android.provider.DocumentsContract.getDocumentId(Uri.parse(mapped)))
        assertEquals("primary:Download/Renamed",android.provider.DocumentsContract.getTreeDocumentId(Uri.parse(mapped)))
        val neighbour="content://com.android.externalstorage.documents/tree/primary%3ADownload%2FSDM-extra"
        assertEquals(neighbour,FolderRenameCoordinator.remapUri(neighbour,old,new))
    }
    @Test fun interruptedFilesystemRenameReplaysWithoutResettingProgress() = runBlocking {
        val base=ApplicationProvider.getApplicationContext<Context>();val prefix="replay-${UUID.randomUUID()}"
        val root=File(base.cacheDir,prefix).apply {mkdirs()};val context=IsolatedContext(base,root,prefix)
        val repo=SqliteDownloadRepository(base,databaseName="$prefix.db");val store=SaveLocationStore(context.getSharedPreferences("sdm_settings",0))
        val categories=CategoryFolderStore(context.getSharedPreferences("sdm_category_folders",0))
        val scheduler=DownloadQueueScheduler(repo,{1},{})
        try {
            repo.awaitInitialized();val old=AppSpecificDownloadsDirectory.from(context);val target=File(root,"After restart")
            val d=Download(id="partial",url="http://127.0.0.1:18765/partial",fileName="partial.bin",destinationPath=File(old,"partial.bin").path,downloadedBytes=3,createdAtEpochMillis=1,state=DownloadState.PAUSED)
            DownloadPartFile.forDestination(File(d.destinationPath!!)).writeBytes(byteArrayOf(1,2,3));repo.insert(d)
            val plan=JSONObject().put("oldPath",old.path).put("newPath",target.path).put("name",target.name).put("oldName",old.name).put("phase","prepared").put("resume",JSONArray(listOf(d.id)))
            context.getSharedPreferences("sdm_folder_migration",0).edit().putString("journal",plan.toString()).commit()
            assertTrue(old.renameTo(target)) // process dies before marking renamed
            assertTrue(FolderRenameCoordinator(context,repo,scheduler,store,categories).recover())
            assertEquals(3L,repo.get(d.id)!!.downloadedBytes);assertEquals(DownloadState.QUEUED,repo.get(d.id)!!.state)
            assertArrayEquals(byteArrayOf(1,2,3),DownloadPartFile.forDestination(File(repo.get(d.id)!!.destinationPath!!)).readBytes())
            assertTrue(FolderRenameCoordinator(context,repo,scheduler,store,categories).recover())
        } finally {
            repo.close();base.deleteDatabase("$prefix.db");root.deleteRecursively()
            listOf("sdm_settings","sdm_category_folders","sdm_folder_migration").forEach {context.getSharedPreferences(it,0).edit().clear().commit()};FolderRenameCoordinator.isPending(context)
        }
    }
}
