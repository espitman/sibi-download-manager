package com.espitman.sdm.torrent

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.*
import com.espitman.sdm.storage.*
import com.espitman.sdm.download.CompletedFileDeleteCoordinator
import com.frostwire.jlibtorrent.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class TorrentDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun await(label: String, predicate: suspend () -> Boolean) = withTimeout(60_000) {
        try { while (!predicate()) delay(200) } catch (timeout: TimeoutCancellationException) { throw AssertionError("Timed out: $label", timeout) }
    }
    private fun encode(value: Any): ByteArray = ByteArrayOutputStream().apply {
        fun writeValue(v: Any) { when(v) {
            is String -> writeValue(v.toByteArray())
            is ByteArray -> { write("${v.size}:".toByteArray()); write(v) }
            is Number -> write("i${v}e".toByteArray())
            is List<*> -> { write('l'.code); v.forEach { writeValue(it!!) }; write('e'.code) }
            is Map<*,*> -> { write('d'.code); v.entries.sortedBy { it.key.toString() }.forEach { writeValue(it.key!!); writeValue(it.value!!) }; write('e'.code) }
            else -> error("Unsupported fixture value")
        } }
        writeValue(value)
    }.toByteArray()
    private inner class Fixture(privateTorrent: Boolean = true, persistedBase: File? = null, seedPort: Int? = null, trackerPort: Int? = null) : AutoCloseable {
        val base = (persistedBase ?: File(context.cacheDir, "torrent-qa-${UUID.randomUUID()}")).apply { mkdirs() }
        val first = ByteArray(2 * 1024 * 1024) { (it * 31 % 251).toByte() }
        val second = ByteArray(1024 * 1024) { (it * 17 % 239).toByte() }
        val port = seedPort ?: ServerSocket(0).use { it.localPort }
        val tracker = ServerSocket(trackerPort ?: 0)
        val trackerUrl = "http://127.0.0.1:${tracker.localPort}/announce"
        val bytes: ByteArray
        val contents: TorrentContents
        private var bound = false
        private val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {}
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        init {
            val files = File(base, "seed/QA-bundle").apply { mkdirs() }
            File(files, "first.bin").writeBytes(first); File(files, "second.bin").writeBytes(second)
            val joined = first + second
            val hashes = ByteArrayOutputStream()
            joined.asList().chunked(65536).forEach { chunk -> hashes.write(MessageDigest.getInstance("SHA-1").digest(chunk.toByteArray())) }
            bytes = encode(mapOf("announce" to trackerUrl, "info" to mapOf("name" to "QA-bundle", "private" to if (privateTorrent) 1 else 0, "piece length" to 65536,
                "pieces" to hashes.toByteArray(), "files" to listOf(mapOf("length" to first.size, "path" to listOf("first.bin")), mapOf("length" to second.size, "path" to listOf("second.bin"))))))
            contents = TorrentStore.inspect(bytes)
            thread(isDaemon=true, name="torrent-qa-tracker") {
                while (!tracker.isClosed) try {
                    tracker.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader(); val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        val peer=byteArrayOf(127,0,0,1,(port shr 8).toByte(),port.toByte())
                        val body=encode(mapOf("interval" to 1,"complete" to 1,"incomplete" to 0,"peers" to if ("left=0&" in request || "&port=$port&" in request) ByteArray(0) else peer))
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
                    }
                } catch (_: Exception) {}
            }
            File(base, "fixture.torrent").writeBytes(bytes)
        }
        suspend fun ready() {
            if (!bound) {
                File(base,"ready").delete()
                bound=context.bindService(android.content.Intent(context, TorrentTestSeederService::class.java)
                    .putExtra("fixture",base.path).putExtra("port",port), connection, Context.BIND_AUTO_CREATE)
                check(bound)
            }
            await("local seeder") { File(base,"ready").exists() }
        }
        override fun close() {
            tracker.close()
            if (bound) {
                context.unbindService(connection)
                val deadline=System.currentTimeMillis()+10_000
                while (File(base,"ready").exists() && System.currentTimeMillis()<deadline) Thread.sleep(50)
            }
            base.deleteRecursively()
        }
    }
    private suspend fun isolated(block: suspend (SqliteDownloadRepository) -> Unit) {
        val name="torrent-qa-${UUID.randomUUID()}.db"
        val repo=SqliteDownloadRepository(context,databaseName=name)
        try {repo.awaitInitialized();block(repo)} finally {repo.close();context.deleteDatabase(name)}
    }
    @Test fun magnetMetadataComesFromRealPeerWithoutDownloadingPayload() = runBlocking {
        Fixture(privateTorrent=false).use { f ->
            f.ready()
            val previousResolvers=context.cacheDir.listFiles().orEmpty().filter { it.name.startsWith("torrent-resolve-") }.map { it.name }.toSet()
            val result=TorrentResolver.magnet(context, f.contents.magnet)
            assertEquals(f.contents.files, result.files)
            assertTrue(TorrentMagnet.isValid(result.magnet))
            assertFalse(context.cacheDir.listFiles().orEmpty().any { it.name.startsWith("torrent-resolve-") && it.name !in previousResolvers })
        }
    }
    @Test fun selectedFilesPauseResumeNativeRestartAndSeedingSwitch() = runBlocking {
        Fixture().use { f -> f.ready(); isolated { repo ->
            val id=UUID.randomUUID().toString(); val root=File(f.base,"client").apply {mkdirs()};val store=TorrentStore(context)
            val chosen=f.contents.files.first {it.path.endsWith("first.bin")}
            store.create(id,f.contents,TorrentSelection(setOf(chosen.index),seed=true),root)
            try {
                repo.insert(Download(id=id,url=f.contents.magnet,fileName="QA-bundle",destinationPath=root.path,totalBytes=f.first.size.toLong(),createdAtEpochMillis=System.currentTimeMillis(),speedLimitBytesPerSecond=96*1024L))
                var job=launch(Dispatchers.IO) {TorrentTransferEngine(context).execute(id,repo,{false},{null})}
                try {
                    await("verified progress") {
                        val row=repo.get(id)!!
                        check(row.state != DownloadState.FAILED) { row.error.orEmpty() }
                        row.downloadedBytes>=65536
                    }
                    job.cancelAndJoin()
                    val paused=repo.get(id)!!;assertEquals(DownloadState.PAUSED,paused.state);assertTrue(paused.downloadedBytes>0)
                    assertTrue(File(store.directory(id),"resume.data").length()>0)
                    // Network policy interruption must retain verified pieces and become eligible again.
                    repo.resumePaused(id,System.currentTimeMillis())
                    job=launch(Dispatchers.IO) {TorrentTransferEngine(context).execute(id,repo,{false},{DownloadPauseCause.NETWORK_POLICY})}
                    await("network-policy transfer") {repo.get(id)!!.state==DownloadState.DOWNLOADING}
                    job.cancelAndJoin()
                    assertEquals(DownloadPauseCause.NETWORK_POLICY,repo.get(id)!!.pauseCause)
                    assertTrue(repo.get(id)!!.downloadedBytes>=paused.downloadedBytes)
                    assertTrue(repo.requeueNetworkPolicyPaused(System.currentTimeMillis()).any {it.id==id})
                    // The engine has released and stopped its native session. Resume opens a new session.
                    repo.resumePaused(id,System.currentTimeMillis())
                    job=launch(Dispatchers.IO) {TorrentTransferEngine(context).execute(id,repo,{false},{null})}
                    await("seeding") {TorrentRuntime.telemetry.value[id]?.seeding==true}
                    assertEquals(DownloadState.DOWNLOADING,repo.get(id)!!.state)
                    store.saveSelection(id,store.selection(id).copy(seed=false))
                    withTimeout(60_000) {job.join()}
                    val completed=repo.get(id)!!;assertEquals(completed.error,DownloadState.COMPLETED,completed.state)
                    assertArrayEquals(f.first,TorrentPaths.resolve(root,chosen.path).readBytes())
                    val excluded=TorrentPaths.resolve(root,f.contents.files.last().path)
                    assertTrue(!excluded.exists() || excluded.length()==0L)
                    assertEquals(CompletedDestinationPresence.Readable,CompletedDestinationAccess.classifyDownload(completed))
                    CompletedFileDeleteCoordinator.delete(id,repo)
                    assertFalse(root.exists());assertNull(repo.get(id))
                } finally {job.cancelAndJoin()}
            } finally {store.remove(id)}
        } }
    }
    @Test fun rejectsCorruptMetadataAndInvalidSelectionsWithoutTouchingExistingFiles() = runBlocking {
        assertThrows(IllegalArgumentException::class.java) {TorrentStore.inspect("bad".toByteArray())}
        Fixture().use { f -> isolated {repo ->
            val id=UUID.randomUUID().toString(); val root=File(f.base,"invalid").apply {mkdirs()}; val marker=File(root,"existing.txt").apply {writeText("keep")}
            val store=TorrentStore(context);store.create(id,f.contents,TorrentSelection(setOf(999)))
            try {
                repo.insert(Download(id=id,url=f.contents.magnet,fileName="QA-bundle",destinationPath=root.path,totalBytes=1,createdAtEpochMillis=System.currentTimeMillis()))
                TorrentTransferEngine(context).execute(id,repo,{false},{null})
                assertEquals(DownloadState.FAILED,repo.get(id)!!.state);assertEquals("keep",marker.readText())
            } finally {store.remove(id)}
        } }
    }
    @Test fun storageExhaustionFailsWithoutTruncatingExistingData() = runBlocking {
        Fixture().use { f -> isolated { repo ->
            val id=UUID.randomUUID().toString();val root=File(f.base,"full").apply {mkdirs()};val marker=File(root,"keep.txt").apply {writeText("unchanged")}
            val store=TorrentStore(context);store.create(id,f.contents,TorrentSelection(setOf(0)),root)
            try {
                repo.insert(Download(id=id,url=f.contents.magnet,fileName="QA-bundle",destinationPath=root.path,totalBytes=f.first.size.toLong(),createdAtEpochMillis=System.currentTimeMillis()))
                val full=object : StorageCapacityProbe {
                    override fun queryLocalPath(path:File)=StorageCapacity(availableBytes=0)
                    override fun queryTree(treeUri:String)=StorageCapacity(availableBytes=0)
                }
                TorrentTransferEngine(context,full).execute(id,repo,{false},{null})
                assertEquals(DownloadState.FAILED,repo.get(id)!!.state)
                assertTrue(repo.get(id)!!.error.orEmpty().contains("storage"));assertEquals("unchanged",marker.readText())
            } finally {store.remove(id)}
        } }
    }

    @Test fun selectedFilesPublishToSafAndRecoverFromFullProviderWithoutDuplicates() = runBlocking {
        SdmTestDocumentsProvider.reset(context)
        try {Fixture().use { f -> isolated { repo ->
            val id=UUID.randomUUID().toString();val root=File(f.base,"publish").apply {mkdirs()};val store=TorrentStore(context)
            val chosen=f.contents.files.first();TorrentPaths.resolve(root,chosen.path).apply {parentFile!!.mkdirs();writeBytes(f.first)}
            store.create(id,f.contents,TorrentSelection(setOf(chosen.index)),root)
            val tree=SdmTestDocumentsProvider.treeUri().toString()
            val row=Download(id=id,url=f.contents.magnet,fileName="QA-bundle",destinationPath=root.path,destinationTreeUri=tree,totalBytes=f.first.size.toLong(),createdAtEpochMillis=System.currentTimeMillis())
            repo.insert(row)
            try {
                SdmTestDocumentsProvider.writeFailure=true
                assertThrows(java.io.IOException::class.java) {runBlocking {TorrentPublisher.publish(context,row,f.contents,store)}}
                assertTrue(store.selection(id).published.isEmpty())
                val savedRoot=store.selection(id).rootDocument
                SdmTestDocumentsProvider.writeFailure=false
                val published=TorrentPublisher.publish(context,row,f.contents,store)
                assertEquals(savedRoot,published)
                val uri=android.net.Uri.parse(store.selection(id).published.getValue(chosen.index))
                assertArrayEquals(f.first,context.contentResolver.openInputStream(uri)!!.use {it.readBytes()})
                assertEquals(published,TorrentPublisher.publish(context,row,f.contents,store))
                assertEquals(1,store.selection(id).published.size)
                val documents=DocumentsContractContentDocuments(context.contentResolver,{it==tree})
                assertEquals(CompletedDestinationPresence.Readable,documents.directoryPresence(published,tree))
                assertTrue(documents.delete(published,tree) is ContentDocumentMutation.Success)
                // Android revokes tree-child access when the provider removes the directory.
                assertEquals(CompletedDestinationPresence.AccessUnavailable,documents.directoryPresence(published,tree))
            } finally {store.remove(id)}
        } } } finally {SdmTestDocumentsProvider.reset(context)}
    }

    @Test fun torrentFileAndHttpMetadataAndCancelledMagnet() = runBlocking {
        Fixture().use { f ->
            assertEquals(f.contents.files,TorrentResolver.file(context,android.net.Uri.fromFile(File(f.base,"fixture.torrent"))).files)
            ServerSocket(0).use { server ->
                val responder=thread(isDaemon=true) {
                    server.accept().use { socket ->
                        val reader=socket.getInputStream().bufferedReader();reader.readLine()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        socket.getOutputStream().apply {write("HTTP/1.1 200 OK\r\nContent-Length: ${f.bytes.size}\r\nConnection: close\r\n\r\n".toByteArray());write(f.bytes);flush()}
                    }
                }
                assertEquals(f.contents.files,TorrentResolver.link(context,"http://127.0.0.1:${server.localPort}/fixture.torrent").files)
                responder.join(5000)
            }
        }
        val previous=context.cacheDir.listFiles().orEmpty().map {it.name}.toSet()
        val job=launch(Dispatchers.IO) {TorrentResolver.magnet(context,"magnet:?xt=urn:btih:"+"a".repeat(40))}
        delay(500);job.cancelAndJoin()
        assertFalse(context.cacheDir.listFiles().orEmpty().any {it.name.startsWith("torrent-resolve-") && it.name !in previous})
    }

    @Test fun torrentBackupRestoresSelectionAndRollsBackInvalidMetadata() = runBlocking {
        Fixture().use { f -> isolated { repo ->
            val output=File(f.base,"restore").apply {mkdirs()};val existing=File(output,"keep.txt").apply {writeText("unchanged")}
            val store=TorrentStore(context)
            val allocator=AppPrivateDestinationAllocator({output})
            val metadata=object : com.espitman.sdm.network.DownloadMetadataRetriever {
                override suspend fun retrieve(url:String): com.espitman.sdm.network.DownloadMetadataResult = error("HTTP metadata must not be requested")
            }
            val scheduler=com.espitman.sdm.download.DownloadQueueScheduler(repo,{1},{error("Restored torrents must remain paused")})
            val coordinator=com.espitman.sdm.data.LinkArchiveCoordinator(repo,allocator,metadata,scheduler,store)
            val row=Download(url=f.contents.magnet,fileName="QA-bundle",totalBytes=f.first.size.toLong(),createdAtEpochMillis=1)
            val snapshot=com.espitman.sdm.data.DownloadBackupCodec.TorrentSnapshot(f.bytes,setOf(0),false)
            val encoded=com.espitman.sdm.data.DownloadBackupCodec.encode(listOf(row),null,1,torrents=mapOf(row.url to snapshot))
            val backup=com.espitman.sdm.data.DownloadBackupCodec.decode(encoded)
            assertEquals(1,coordinator.restore(backup.downloads,backup.torrents))
            val restored=repo.downloads.value.single()
            try {
                assertEquals(DownloadState.PAUSED,restored.state);assertEquals(0L,restored.downloadedBytes)
                assertEquals(setOf(0),store.selection(restored.id).selected)
                assertArrayEquals(f.bytes,store.metadata(restored.id))
                assertEquals(0,coordinator.restore(backup.downloads,backup.torrents))
                val bad=row.copy(url="magnet:?xt=urn:btih:"+"b".repeat(40))
                val names=output.list()!!.toSet()
                assertThrows(IllegalArgumentException::class.java) {runBlocking {coordinator.restore(listOf(bad),mapOf(bad.url to snapshot))}}
                assertEquals(names,output.list()!!.toSet());assertEquals("unchanged",existing.readText())
            } finally {store.cleanupWorkingFiles(restored.id);store.remove(restored.id)}
        } }
    }

    /** Host kills the process after the ready marker; this deliberately cannot pass in one process. */
    @Test fun stageAbruptProcessDeath(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("torrentPhase") == "stage")
        val f=Fixture();f.ready()
        val name="torrent-process-death-qa.db"
        context.deleteDatabase(name)
        val repo=SqliteDownloadRepository(context,databaseName=name);repo.awaitInitialized()
        val id=UUID.randomUUID().toString();val root=File(f.base,"client").apply {mkdirs()}
        val chosen=f.contents.files.first();val store=TorrentStore(context)
        store.create(id,f.contents,TorrentSelection(setOf(chosen.index)),root)
        repo.insert(Download(id=id,url=f.contents.magnet,fileName="QA-bundle",destinationPath=root.path,
            totalBytes=f.first.size.toLong(),createdAtEpochMillis=System.currentTimeMillis(),speedLimitBytesPerSecond=32*1024L))
        launch(Dispatchers.IO) {TorrentTransferEngine(context).execute(id,repo,{false},{null})}
        await("partial data before process death") {repo.get(id)!!.downloadedBytes>=262144}
        val marker=org.json.JSONObject().put("id",id).put("base",f.base.path).put("port",f.port)
            .put("trackerPort",f.tracker.localPort).put("bytes",repo.get(id)!!.downloadedBytes)
        File(context.filesDir,"torrent-process-death-qa.json").writeText(marker.toString())
        delay(120_000)
        error("Host did not kill the process")
    }
    @Test fun recoverAfterAbruptProcessDeath() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("torrentPhase") == "recover")
        val markerFile=File(context.filesDir,"torrent-process-death-qa.json")
        val marker=org.json.JSONObject(markerFile.readText());val id=marker.getString("id")
        val name="torrent-process-death-qa.db";val store=TorrentStore(context)
        Fixture(persistedBase=File(marker.getString("base")),seedPort=marker.getInt("port"),trackerPort=marker.getInt("trackerPort")).use { f ->
            f.ready();val repo=SqliteDownloadRepository(context,databaseName=name)
            try {
                repo.awaitInitialized();val interrupted=repo.get(id)!!
                assertEquals(DownloadState.DOWNLOADING,interrupted.state)
                assertTrue(interrupted.downloadedBytes>=marker.getLong("bytes"))
                assertNotNull(repo.requeueInterruptedActive(id,System.currentTimeMillis()))
                repo.updateSpeedLimit(id,null,System.currentTimeMillis())
                TorrentTransferEngine(context).execute(id,repo,{false},{null})
                val completed=repo.get(id)!!;assertEquals(completed.error,DownloadState.COMPLETED,completed.state)
                assertArrayEquals(f.first,TorrentPaths.resolve(File(completed.destinationPath!!),f.contents.files.first().path).readBytes())
            } finally {repo.close();context.deleteDatabase(name);store.remove(id);markerFile.delete()}
        }
    }

    @Test fun cleanupVisualFixture(): Unit = runBlocking {
        val id=InstrumentationRegistry.getArguments().getString("torrentVisualId")
        org.junit.Assume.assumeTrue(id != null)
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        val row=repo.get(id!!) ?: return@runBlocking
        check(row.fileName=="SDM Torrent QA" && row.isTorrent && row.state==DownloadState.PAUSED)
        val root=File(row.destinationPath!!)
        check(File(root,".sdm-torrent-owner").readText()==id)
        repo.transition(id,DownloadState.CANCELLED,System.currentTimeMillis())
        assertTrue(com.espitman.sdm.download.IncompleteDownloadDeleteCoordinator.delete(id,repo,{error("Already cancelled")}))
        assertFalse(root.exists());assertNull(repo.get(id))
    }

}
