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
    private inner class Fixture(privateTorrent: Boolean = true, persistedBase: File? = null, seedPort: Int? = null, trackerPort: Int? = null, firstSize: Int = 2 * 1024 * 1024, val seedUploadLimit: Int = 128 * 1024) : AutoCloseable {
        val base = (persistedBase ?: File(context.cacheDir, "torrent-qa-${UUID.randomUUID()}")).apply { mkdirs() }
        val first = ByteArray(firstSize) { (it * 31 % 251).toByte() }
        val second = ByteArray(1024 * 1024) { (it * 17 % 239).toByte() }
        val port = seedPort ?: ServerSocket(0).use { it.localPort }
        val tracker = ServerSocket(trackerPort ?: 0)
        val trackerUrl = "http://127.0.0.1:${tracker.localPort}/announce"
        val bytes: ByteArray
        val contents: TorrentContents
        private var bound = false
        @Volatile private var releasePeerReady = false
        @Volatile private var releasePeerStopped = false
        @Volatile private var releasePeerError: String? = null
        private var releasePeer = false
        private val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName, service: android.os.IBinder) {}
            override fun onServiceDisconnected(name: android.content.ComponentName) {}
        }
        init {
            val files = File(base, "seed/QA-bundle").apply { mkdirs() }
            File(files, "first.bin").writeBytes(first); File(files, "second.bin").writeBytes(second)
            val joined = first + second
            val hashes = ByteArrayOutputStream()
            for (offset in joined.indices step 65536) { val hash=MessageDigest.getInstance("SHA-1"); hash.update(joined,offset,minOf(65536,joined.size-offset)); hashes.write(hash.digest()) }
            bytes = encode(mapOf("announce" to trackerUrl, "info" to mapOf("name" to "QA-bundle", "private" to if (privateTorrent) 1 else 0, "piece length" to 65536,
                "pieces" to hashes.toByteArray(), "files" to listOf(mapOf("length" to first.size, "path" to listOf("first.bin")), mapOf("length" to second.size, "path" to listOf("second.bin"))))))
            contents = TorrentStore.inspect(bytes)
            thread(isDaemon=true, name="torrent-qa-tracker") {
                while (!tracker.isClosed) try {
                    tracker.accept().use { socket ->
                        socket.soTimeout=5000
                        val reader=socket.getInputStream().bufferedReader(); val request = reader.readLine().orEmpty()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        android.util.Log.i("SDMReleaseTorrentQA", "Owned tracker: " + Regex("[?&](port|left|event)=[^& ]+").findAll(request).joinToString { it.value } + " seedPort=" + port)
                        val peer=byteArrayOf(127,0,0,1,(port shr 8).toByte(),port.toByte())
                        val body=encode(mapOf("interval" to 1,"complete" to 1,"incomplete" to 0,"peers" to if (Regex("[?&]port=$port(?:&| )").containsMatchIn(request)) ByteArray(0) else peer))
                        socket.getOutputStream().apply { write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray());write(body);flush() }
                    }
                } catch (_: Exception) {}
            }
            File(base, "fixture.torrent").writeBytes(bytes)
        }
        suspend fun ready() {
            if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) {
                if (!bound) {
                    releasePeer = true
                    val receiver = object : android.os.ResultReceiver(android.os.Handler(android.os.Looper.getMainLooper())) {
                        override fun onReceiveResult(code: Int, data: android.os.Bundle?) {
                            when (code) { 1 -> releasePeerReady = true; 2 -> releasePeerError = data?.getString("error"); 3 -> releasePeerStopped = true }
                        }
                    }
                    val testContext = InstrumentationRegistry.getInstrumentation().context
                    bound = context.bindService(android.content.Intent().setClassName(testContext.packageName,
                        "com.espitman.sdm.torrent.ReleasePeerTestService").putExtra("metadata", bytes)
                        .putExtra("port", port).putExtra("receiver", receiver).putExtra("appPackage",context.packageName)
                        .putExtra("firstSize",first.size).putExtra("secondSize",second.size).putExtra("uploadLimit",seedUploadLimit), connection, Context.BIND_AUTO_CREATE)
                    check(bound) { "Could not bind independent release peer" }
                }
                await("independent release peer") { check(releasePeerError == null) { releasePeerError.orEmpty() }; releasePeerReady }
                return
            }
            if (!bound) {
                File(base,"ready").delete()
                bound=context.bindService(android.content.Intent().setClassName(context, "com.espitman.sdm.torrent.TorrentTestSeederService")
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
                while ((if (releasePeer) !releasePeerStopped else File(base,"ready").exists()) && System.currentTimeMillis()<deadline) Thread.sleep(50)
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
                    assertNull(TorrentRuntime.telemetry.value[id])
                    val savedProgress = checkNotNull(TorrentStore(context).progress(id))
                    assertEquals(paused.downloadedBytes, savedProgress.downloaded)
                    assertEquals(setOf(chosen.index), savedProgress.files.keys)
                    assertEquals(paused.downloadedBytes, savedProgress.files.getValue(chosen.index))
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
    @Test fun globalRateClassIncludesIpv4AndIpv6LocalPeers() {
        TorrentNativeRuntime.prepare(context)
        val session=SessionManager()
        try {
            session.start(SessionParams(TorrentSessionSettings.create(20)));session.stopDht()
            TorrentSessionSettings.applyRateClassToAllPeers(session)
            val filter=session.swig().get_peer_class_filter()
            for(address in listOf("127.0.0.1","192.168.1.9","203.0.113.7","::1","fd00::1","2001:db8::1"))
                assertEquals("Global limit class missing for $address",1L,filter.access(Address(address).swig()) and 1L)
        } finally {session.stop()}
    }
    @Test fun sharedGlobalLimitChangesDuringConcurrentHttpAndTorrent() = runBlocking {
        check(context.packageName.endsWith(".qa")) { "Mixed global test is isolated from user transfers" }
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty())
        val settings=com.espitman.sdm.data.settings.SettingsRepository.get(context);val original=settings.settings.value
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        val access=com.espitman.sdm.web.WebPanelRuntime.access;val pending=access.request("Owned mixed rate test","127.0.0.7");check(access.approve(pending.first.id));val secret=checkNotNull(access.collect(pending.first.id,pending.second));val browser=access.authorize(secret)
        val bridge=com.espitman.sdm.web.WebAppBridge(context);val router=com.espitman.sdm.web.WebRouter(access,{"Fixture"},{null},bridge::handle)
        fun call(path:String,body:org.json.JSONObject?=null,upload:ByteArray?=null):org.json.JSONObject {
            val response=router.handle(com.espitman.sdm.web.LanHttpServer.Request(if(body==null&&upload==null)"GET" else "POST",path,buildMap{put("authorization","Bearer $secret");if(body!=null||upload!=null){put("idempotency-key",UUID.randomUUID().toString());put("content-type",if(upload==null)"application/json" else "application/x-bittorrent")}},upload?:body?.toString()?.toByteArray()?:byteArrayOf(),"127.0.0.7"))
            assertTrue(String(response.body),response.status in setOf(200,202));return org.json.JSONObject(String(response.body))
        }
        val networkTest=InstrumentationRegistry.getArguments().getString("network")=="true"
        val runtime=com.espitman.sdm.web.WebPanelRuntime
        val oldEnabled=runtime.enabled(context);val oldPort=runtime.port(context)
        val networkStage=File(context.externalCacheDir,"mixed-network-stage")
        val networkInfo=File(context.externalCacheDir,"mixed-network-info.json")
        val wifi=context.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java)
        var originalNetworkId:Int?=null
        fun <T> networkPermission(block:()->T):T {
            val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.NETWORK_SETTINGS","android.permission.ACCESS_WIFI_STATE","android.permission.CHANGE_WIFI_STATE","android.permission.ACCESS_FINE_LOCATION")
            try{return block()}finally{automation.dropShellPermissionIdentity()}
        }
        fun connectSaved(id:Int)=networkPermission { check(wifi.enableNetwork(id,true)) { "Android rejected the saved network switch" };wifi.reconnect() }

        val length=64*1024*1024;val origin=ServerSocket(0,16,java.net.InetAddress.getByName("127.0.0.1"));val sockets=java.util.concurrent.CopyOnWriteArrayList<java.net.Socket>()
        val acceptor=thread(isDaemon=true){while(!origin.isClosed)try{val socket=origin.accept();sockets+=socket;thread(isDaemon=true){try{socket.use{client->
            client.soTimeout=5000;val reader=client.getInputStream().bufferedReader();val line=reader.readLine()?:return@use;var range:String?=null
            while(true){val h=reader.readLine()?:break;if(h.isEmpty())break;if(h.startsWith("Range:",true))range=h.substringAfter(':').trim()}
            val bounds=range?.removePrefix("bytes=")?.split('-');val start=bounds?.firstOrNull()?.toIntOrNull()?:0;val end=minOf(bounds?.getOrNull(1)?.toIntOrNull()?:length-1,length-1)
            val out=client.getOutputStream();out.write(("HTTP/1.1 ${if(range==null)"200 OK" else "206 Partial Content"}\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\nETag: \"mixed-v1\"\r\nConnection: close\r\n"+(if(range==null)"" else "Content-Range: bytes $start-$end/$length\r\n")+"\r\n").toByteArray())
            if(!line.startsWith("HEAD")){val block=ByteArray(65536){(start+it).toByte()};var offset=start;while(offset<=end){val count=minOf(block.size,end-offset+1);out.write(block,0,count);offset+=count}}
        }}catch(_:Exception){}finally{sockets.remove(socket)}}}catch(_:Exception){if(origin.isClosed)break}}
        val ids=mutableListOf<String>();var httpDirectory:File?=null
        fun bytes()=repo.downloads.value.filter{it.id in ids}.sumOf{it.downloadedBytes}
        suspend fun measure():Double {val before=bytes();val time=System.nanoTime();delay(8000);return (bytes()-before)*1e9/(System.nanoTime()-time)}
        try { Fixture(firstSize=32*1024*1024,seedUploadLimit=4*1024*1024).use{f->
            f.ready();call("/api/settings",org.json.JSONObject().put("simultaneous",2).put("wifiOnly",false).put("dailyBulkScheduleEnabled",false).put("unlimitedSpeed",false).put("speedLimitMbps",1).put("speedLimitWifiOnly",false))
            val draft=call("/api/torrent/upload",upload=f.bytes).getString("id");await("mixed metadata"){call("/api/torrent/draft/$draft").getString("status")=="ready"}
            val chosen=f.contents.files.first{it.path.endsWith("first.bin")}
            val torrentId=call("/api/torrent/submit",org.json.JSONObject().put("id",draft).put("selected",org.json.JSONArray(listOf(chosen.index))).put("seed",false).put("startNow",false)).getString("id");ids+=torrentId
            val added=call("/api/downloads/add",org.json.JSONObject().put("links",org.json.JSONArray(listOf("http://127.0.0.1:${origin.localPort}/mixed-http.bin"))).put("startNow",false))
            val httpId=added.getJSONArray("results").getJSONObject(0).getString("id");ids+=httpId
            httpDirectory=repo.get(httpId)!!.destinationPath?.let{File(it).parentFile}
            call("/api/download/action",org.json.JSONObject().put("ids",org.json.JSONArray(ids)).put("action","resume"))
            await("both mixed protocols transferring"){ids.all{repo.get(it)!!.state==DownloadState.DOWNLOADING&&repo.get(it)!!.downloadedBytes>=262144}}
            if(!networkTest) {
            delay(2000);val firstOffsets=ids.associateWith{repo.get(it)!!.downloadedBytes};val capped=measure()
            println("MIXED_SAMPLE cap1=$capped before=$firstOffsets after=${ids.associateWith{repo.get(it)!!.downloadedBytes}} states=${ids.map{repo.get(it)!!.state}}")
            assertTrue("Both protocols must gain verified bytes",ids.all{repo.get(it)!!.downloadedBytes>firstOffsets.getValue(it)})
            assertTrue("Mixed global 1 MB/s was exceeded or stalled: $capped",capped in 400000.0..1350000.0)
            call("/api/settings",org.json.JSONObject().put("speedLimitMbps",2));delay(2000);val changed=measure()
            assertTrue("Mixed live 2 MB/s was exceeded or stalled: $changed",changed in 1200000.0..2600000.0)
            println("MIXED_GLOBAL_RATES cap1=$capped cap2=$changed http=${repo.get(httpId)!!.downloadedBytes} torrent=${repo.get(torrentId)!!.downloadedBytes}")
            File(context.externalCacheDir,"mixed-rate-result.json").writeText(org.json.JSONObject().put("cap1Rate",capped).put("cap2Rate",changed).toString())
            }
            if(networkTest) {
                runtime.start(context,18083);await("physical network listener"){runtime.status.value.running}
                val oldAddress=checkNotNull(runtime.status.value.address)
                fun remoteState(address:String):org.json.JSONObject {
                    val connection=java.net.URL("$address/api/state").openConnection() as java.net.HttpURLConnection
                    connection.connectTimeout=3000;connection.readTimeout=3000;connection.setRequestProperty("Authorization","Bearer $secret")
                    try { assertEquals(200,connection.responseCode);return org.json.JSONObject(connection.inputStream.bufferedReader().use{it.readText()}) } finally {connection.disconnect()}
                }
                remoteState(oldAddress)
                val approvalsBefore=access.browsers.value
                val approval=access.request("Owned pending network request","127.0.0.8")
                val offsets=ids.associateWith{repo.get(it)!!.downloadedBytes}
                call("/api/settings",org.json.JSONObject().put("speedLimitMbps",1).put("wifiOnly",true))
                val configured=networkPermission { wifi.configuredNetworks }
                val current=networkPermission { wifi.connectionInfo.networkId };originalNetworkId=current
                val alternate=configured.first { it.SSID.trim('"')=="JABAMA" && it.networkId!=current }
                networkInfo.writeText(org.json.JSONObject().put("before",oldAddress).toString());networkStage.writeText("ready")
                connectSaved(alternate.networkId)
                withTimeout(90000){while(!runtime.status.value.running||runtime.status.value.address==oldAddress)delay(100)}
                val changedAddress=checkNotNull(runtime.status.value.address);remoteState(changedAddress)
                assertEquals(browser.id,access.authorize(secret).id)
                assertEquals(approvalsBefore,access.browsers.value)
                val collected=try{access.collect(approval.first.id,approval.second)}catch(_:com.espitman.sdm.web.AccessDenied){null}
                assertTrue("Network change must never approve a pending request",collected==null);access.deny(approval.first.id)
                assertTrue("Published progress decreased after the physical network change: before=$offsets after=${ids.associateWith{repo.get(it)!!.downloadedBytes}}",ids.all{repo.get(it)!!.downloadedBytes>=offsets.getValue(it)})
                networkInfo.writeText(org.json.JSONObject().put("before",oldAddress).put("after",changedAddress).toString());networkStage.writeText("changed")
                println("PHYSICAL_NETWORK_ADDRESS $oldAddress -> $changedAddress existingApproval=${access.authorize(secret).id==browser.id}")
                delay(15000)
                connectSaved(current)
                withTimeout(90000){while(!runtime.status.value.running||networkPermission { wifi.connectionInfo.networkId }!=current)delay(100)}
                remoteState(checkNotNull(runtime.status.value.address));networkStage.writeText("returned")
            }
            call("/api/settings",org.json.JSONObject().put("unlimitedSpeed",true));await("mixed exact completion"){ids.all{repo.get(it)!!.state==DownloadState.COMPLETED}}
            for((id,expected) in listOf(torrentId to MessageDigest.getInstance("SHA-256").digest(f.first),httpId to MessageDigest.getInstance("SHA-256").apply{repeat(1024){update(ByteArray(65536){it.toByte()})}}.digest())) {
                val row=repo.get(id)!!;val location=if(row.isTorrent)TorrentStore(context).selection(id).published[chosen.index]?:TorrentPaths.resolve(File(row.destinationPath!!),chosen.path).path else row.destinationPath!!
                val stream=if(location.startsWith("content:"))context.contentResolver.openInputStream(android.net.Uri.parse(location))!! else File(location).inputStream()
                val actual=MessageDigest.getInstance("SHA-256");stream.use{input->val buffer=ByteArray(65536);while(true){val count=input.read(buffer);if(count<0)break;actual.update(buffer,0,count)}};assertArrayEquals(if(row.isTorrent)"Torrent exact bytes" else "HTTP exact resumed bytes",expected,actual.digest())
            }
        }} finally {
            ids.forEach{id->com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context,id);val row=repo.get(id);row?.destinationPath?.let{path->if(!path.startsWith("content:")){val file=File(path);if(row.isTorrent)file.deleteRecursively() else{file.delete();com.espitman.sdm.download.DownloadPartFile.forDestination(file).delete()}}};repo.delete(id);TorrentStore(context).remove(id)}
            settings.update{original};com.espitman.sdm.download.DailyBulkSchedule.arm(context);if(networkTest){originalNetworkId?.let{runCatching{connectSaved(it)}};if(oldEnabled)runtime.start(context,oldPort) else runtime.stop(context)};origin.close();sockets.forEach{runCatching{it.close()}};acceptor.join(1000);bridge.close();access.revoke(browser.id)
        }
        assertTrue(repo.downloads.value.isEmpty())
    }
    @Test fun webUploadSelectionPauseResumeCompletionAndSeedingUseRealPeer() = webTransfer(false)
    @Test fun webMagnetSelectionPauseResumeCompletionAndSeedingUseRealPeer() = webTransfer(true)
    @Test fun webTorrentTransferSurvivesListenerOutage() = webTransfer(false, true)
    private fun webTransfer(magnet: Boolean, listenerOutage: Boolean = false) = runBlocking {
        val repository = com.espitman.sdm.data.AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originals = repository.downloads.value
        org.junit.Assume.assumeTrue("Requires an idle user queue", originals.all { it.state in setOf(DownloadState.PAUSED, DownloadState.COMPLETED, DownloadState.FAILED, DownloadState.CANCELLED) })
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        if (listenerOutage) org.junit.Assume.assumeTrue(com.espitman.sdm.web.WebPanelRuntime.enabled(context))
        val access = com.espitman.sdm.web.WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM owned torrent transfer fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = com.espitman.sdm.web.WebAppBridge(context)
        val router = com.espitman.sdm.web.WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        var ownedId: String? = null
        fun call(path: String, body: org.json.JSONObject? = null, upload: ByteArray? = null): org.json.JSONObject {
            android.util.Log.i("SDMReleaseTorrentQA", "Command: $path ${body?.optString("action").orEmpty()}")
            val headers = mutableMapOf("authorization" to "Bearer $secret")
            if (body != null || upload != null) {
                headers["idempotency-key"] = UUID.randomUUID().toString()
                headers["content-type"] = if (upload != null) "application/x-bittorrent" else "application/json"
            }
            val response = router.handle(com.espitman.sdm.web.LanHttpServer.Request(if (body != null || upload != null) "POST" else "GET", path, headers, upload ?: body?.toString()?.toByteArray() ?: byteArrayOf(), "127.0.0.1"))
            assertEquals(String(response.body), if (upload != null || path == "/api/torrent/resolve") 202 else 200, response.status)
            return org.json.JSONObject(String(response.body))
        }
        fun action(id: String, value: String) = call("/api/download/action", org.json.JSONObject().put("ids", org.json.JSONArray(listOf(id))).put("action", value))
        try {
            Fixture(privateTorrent = !magnet).use { f ->
                f.ready()
                if (magnet) {
                    val cancelled = call("/api/torrent/resolve", org.json.JSONObject().put("link", f.contents.magnet)).getString("id")
                    call("/api/torrent/cancel", org.json.JSONObject().put("id", cancelled))
                    assertEquals(originals, repository.downloads.value)
                }
                val draft = if (magnet) call("/api/torrent/resolve", org.json.JSONObject().put("link", f.contents.magnet)).getString("id")
                    else call("/api/torrent/upload", upload = f.bytes).getString("id")
                await("web metadata ready") {
                    val review = call("/api/torrent/draft/$draft")
                    check(review.getString("status") != "failed") { review.optString("error") }
                    review.getString("status") == "ready"
                }
                val chosen = f.contents.files.first { it.path.endsWith("first.bin") }
                val id = call("/api/torrent/submit", org.json.JSONObject().put("id", draft).put("selected", org.json.JSONArray(listOf(chosen.index))).put("seed", true).put("startNow", false)).getString("id")
                ownedId = id
                assertEquals(DownloadState.PAUSED, repository.get(id)!!.state)
                assertEquals(0L, repository.get(id)!!.downloadedBytes)
                repository.updateSpeedLimit(id, 96 * 1024L, System.currentTimeMillis())
                assertTrue(action(id, "resume").getJSONArray("results").getJSONObject(0).getBoolean("accepted"))
                await("web-started verified pieces") { val row = repository.get(id)!!; check(row.state != DownloadState.FAILED) { row.error.orEmpty() }; row.downloadedBytes >= 65536 }
                if (listenerOutage) {
                    val runtime = com.espitman.sdm.web.WebPanelRuntime
                    val port = runtime.port(context)
                    val approvals = runtime.access.browsers.value.map { it.id }.toSet()
                    val before = repository.get(id)!!.downloadedBytes
                    try {
                        runtime.stop(context)
                        await("torrent listener stopped") { !runtime.status.value.running }
                        kotlinx.coroutines.delay(4000)
                        val during = repository.get(id)!!
                        assertTrue("Torrent must gain verified bytes with panel stopped", during.downloadedBytes > before)
                        assertEquals(DownloadState.DOWNLOADING, during.state)
                        assertEquals(approvals, runtime.access.browsers.value.map { it.id }.toSet())
                    } finally {
                        runtime.start(context, port)
                        await("torrent listener restored") { runtime.status.value.running }
                    }
                    assertEquals(port, runtime.status.value.port)
                    assertEquals(approvals, runtime.access.browsers.value.map { it.id }.toSet())
                }
                action(id, "pause")
                await("web pause persisted") { repository.get(id)!!.state == DownloadState.PAUSED && TorrentRuntime.telemetry.value[id] == null }
                val saved = repository.get(id)!!.downloadedBytes
                assertTrue(saved > 0)
                assertEquals(saved, TorrentStore(context).progress(id)!!.downloaded)
                action(id, "resume")
                var diagnosticAt = 0L
                await("web-resumed seeding") {
                    val row = repository.get(id)!!
                    check(row.state != DownloadState.FAILED) { row.error.orEmpty() }
                    if (System.currentTimeMillis() - diagnosticAt > 5000) {
                        val telemetry = TorrentRuntime.telemetry.value[id]
                        android.util.Log.i("SDMReleaseTorrentQA", "Owned resume: state=${row.state}, bytes=${row.downloadedBytes}, peers=${telemetry?.peers}, rate=${telemetry?.downloadRate}, checking=${telemetry?.checking}")
                        diagnosticAt = System.currentTimeMillis()
                    }
                    TorrentRuntime.telemetry.value[id]?.seeding == true
                }
                val details = call("/api/torrent/details/$id")
                assertTrue(details.getBoolean("seed"))
                assertEquals(2, details.getJSONArray("files").length())
                assertTrue(action(id, "pause").getJSONArray("results").getJSONObject(0).getBoolean("accepted"))
                await("web stop seeding retains completion") {
                    repository.get(id)!!.state == DownloadState.COMPLETED && TorrentRuntime.telemetry.value[id] == null
                }
                val completed = repository.get(id)!!
                assertEquals(f.first.size.toLong(), completed.downloadedBytes)
                val store = TorrentStore(context)
                val uri = store.selection(id).published[chosen.index]
                val actual = if (uri != null) context.contentResolver.openInputStream(android.net.Uri.parse(uri))!!.use { it.readBytes() }
                    else TorrentPaths.resolve(File(completed.destinationPath), chosen.path).readBytes()
                assertArrayEquals(f.first, actual)
                assertEquals(originals, repository.downloads.value.filter { it.id != id })
                assertTrue(call("/api/downloads/remove", org.json.JSONObject().put("ids", org.json.JSONArray(listOf(id))).put("confirmed", true).put("deleteFiles", true)).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
                ownedId = null
            }
        } finally {
            ownedId?.let { id ->
                com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context, id)
                await("owned fixture stopped") { TorrentRuntime.telemetry.value[id] == null }
                assertTrue(call("/api/downloads/remove", org.json.JSONObject().put("ids", org.json.JSONArray(listOf(id))).put("confirmed", true).put("deleteFiles", true)).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
            }
            bridge.close()
            access.revoke(browser.id)
        }
        assertEquals(originals, repository.downloads.value)
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
        File(context.externalCacheDir,"torrent-process-ready").writeText("ready")
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
            } finally {repo.close();context.deleteDatabase(name);store.remove(id);markerFile.delete();File(context.externalCacheDir,"torrent-process-ready").delete()}
        }
    }

    private fun withIsolatedWebCommands(block: ((String, org.json.JSONObject?, ByteArray?) -> com.espitman.sdm.web.LanHttpServer.Response) -> Unit) {
        val access=com.espitman.sdm.web.WebPanelRuntime.access
        val (pending,ticket)=access.request("Owned torrent restart commands","127.0.0.9")
        check(access.approve(pending.id));val secret=checkNotNull(access.collect(pending.id,ticket));val ownedBrowser=access.authorize(secret)
        val bridge=com.espitman.sdm.web.WebAppBridge(context)
        val router=com.espitman.sdm.web.WebRouter(access,{"Fixture"},{null},bridge::handle)
        try { block { path,body,bytes ->
            router.handle(com.espitman.sdm.web.LanHttpServer.Request(if(body==null&&bytes==null)"GET" else "POST",path,
                buildMap {put("authorization","Bearer $secret");if(body!=null||bytes!=null){put("idempotency-key",UUID.randomUUID().toString());put("content-type",if(bytes!=null)"application/x-bittorrent" else "application/json")}},
                bytes?:body?.toString()?.toByteArray()?:byteArrayOf(),"127.0.0.9"))
        } } finally {bridge.close();access.revoke(ownedBrowser.id)}
    }
    private fun restartFingerprint(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun stageWebServiceTorrentProcessDeath(): Unit = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("torrentPhase")=="web-stage")
        org.junit.Assume.assumeTrue(com.espitman.sdm.data.settings.SettingsRepository.get(context).settings.value.autoResume)
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        val original=repo.downloads.value
        check(original.all {it.state in setOf(DownloadState.PAUSED,DownloadState.FAILED,DownloadState.COMPLETED,DownloadState.CANCELLED)})
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        val f=Fixture(privateTorrent=false);f.ready()
        var id="";var folderName=""
        val marker=org.json.JSONObject().put("base",f.base.path).put("port",f.port).put("trackerPort",f.tracker.localPort)
            .put("originals",restartFingerprint(original.sortedBy {it.id}.toString()))
            .put("browsers",restartFingerprint(com.espitman.sdm.web.WebPanelRuntime.access.browsers.value.sortedBy {it.id}.toString()))
            .put("settings",restartFingerprint(context.getSharedPreferences("sdm_settings",0).all.toSortedMap().toString()))
        withIsolatedWebCommands { request ->
            fun json(response:com.espitman.sdm.web.LanHttpServer.Response)=org.json.JSONObject(String(response.body))
            val roots=json(request("/api/folders",null,null)).getJSONArray("folders")
            val root=(0 until roots.length()).map {roots.getJSONObject(it)}.first {it.getString("kind")=="app"}
            folderName="SDM-web-restart-${UUID.randomUUID()}"
            val created=request("/api/folders/create",org.json.JSONObject().put("parent",root.getString("id")).put("name",folderName),null)
            assertEquals(String(created.body),200,created.status)
            val folder=json(created).getString("id")
            val upload=request("/api/torrent/upload",null,f.bytes);assertEquals(202,upload.status)
            val draft=json(upload).getString("id")
            kotlinx.coroutines.runBlocking {await("web restart metadata") {json(request("/api/torrent/draft/$draft",null,null)).optString("status")=="ready"}}
            val submit=request("/api/torrent/submit",org.json.JSONObject().put("id",draft).put("selected",org.json.JSONArray(listOf(f.contents.files.first().index))).put("seed",false).put("startNow",false).put("folder",folder),null)
            assertEquals(String(submit.body),200,submit.status);id=json(submit).getString("id")
            assertEquals(200,request("/api/download/limit",org.json.JSONObject().put("id",id).put("unlimited",false).put("value","32").put("unit","KB/s"),null).status)
        }
        marker.put("id",id).put("folder",folderName)
        File(context.filesDir,"web-torrent-process-qa.json").writeText(marker.toString())
        context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        File(context.externalCacheDir,"web-torrent-process-prepared").writeText(repo.get(id)!!.fileName)
        await("Chrome starts web torrent and stores partial progress") {repo.get(id)!!.downloadedBytes>=262144}
        marker.put("bytes",repo.get(id)!!.downloadedBytes)
        File(context.filesDir,"web-torrent-process-qa.json").writeText(marker.toString())
        File(context.externalCacheDir,"web-torrent-process-ready").writeText("ready")
        delay(120000);error("Host must interrupt the staged process")
    }
    @Test fun recoverWebServiceTorrentProcessDeath() = runBlocking {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("torrentPhase")=="web-recover")
        val markerFile=File(context.filesDir,"web-torrent-process-qa.json");val marker=org.json.JSONObject(markerFile.readText());val id=marker.getString("id")
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        assertEquals(DownloadState.DOWNLOADING,repo.get(id)!!.state)
        assertTrue(repo.get(id)!!.downloadedBytes>=marker.getLong("bytes"))
        Fixture(privateTorrent=false,persistedBase=File(marker.getString("base")),seedPort=marker.getInt("port"),trackerPort=marker.getInt("trackerPort")).use {f ->
            f.ready()
            try {
                withIsolatedWebCommands {request ->assertEquals(200,request("/api/download/limit",org.json.JSONObject().put("id",id).put("unlimited",true),null).status)}
                context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                await("production service automatic torrent recovery") {repo.get(id)!!.state==DownloadState.COMPLETED}
                val row=repo.get(id)!!;assertEquals(f.first.size.toLong(),row.downloadedBytes)
                val chosen=f.contents.files.first();val published=TorrentStore(context).selection(id).published[chosen.index]
                val bytes=if(published!=null)context.contentResolver.openInputStream(android.net.Uri.parse(published))!!.use {it.readBytes()} else TorrentPaths.resolve(File(row.destinationPath!!),chosen.path).readBytes()
                assertArrayEquals(f.first,bytes)
                assertEquals(marker.getString("originals"),restartFingerprint(repo.downloads.value.filter {it.id!=id}.sortedBy {it.id}.toString()))
                assertEquals(marker.getString("browsers"),restartFingerprint(com.espitman.sdm.web.WebPanelRuntime.access.browsers.value.sortedBy {it.id}.toString()))
                assertEquals(marker.getString("settings"),restartFingerprint(context.getSharedPreferences("sdm_settings",0).all.toSortedMap().toString()))
                delay(10000)
            } finally {
                withIsolatedWebCommands {request ->assertEquals(200,request("/api/downloads/remove",org.json.JSONObject().put("ids",org.json.JSONArray(listOf(id))).put("confirmed",true).put("deleteFiles",true),null).status)}
                File(AppSpecificDownloadsDirectory.from(context),marker.getString("folder")).deleteRecursively()
                markerFile.delete();File(context.externalCacheDir,"web-torrent-process-prepared").delete();File(context.externalCacheDir,"web-torrent-process-ready").delete()
            }
        }
    }

    @Test fun externalChromeTabCloseKeepsServiceTorrentRunning() = runBlocking {
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        check(originals.all {it.state in setOf(DownloadState.PAUSED,DownloadState.FAILED,DownloadState.COMPLETED,DownloadState.CANCELLED)})
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        val browsers=com.espitman.sdm.web.WebPanelRuntime.access.browsers.value
        val settings=context.getSharedPreferences("sdm_settings",0).all
        val prepared=File(context.externalCacheDir,"web-tab-close-prepared")
        val closing=File(context.externalCacheDir,"web-tab-close-confirmed")
        val ready=File(context.externalCacheDir,"web-tab-close-ready")
        prepared.delete();closing.delete();ready.delete()
        var id:String?=null;var folderName:String?=null
        Fixture(privateTorrent=false).use {f ->
            f.ready()
            try {
                withIsolatedWebCommands {request ->
                    fun json(response:com.espitman.sdm.web.LanHttpServer.Response)=org.json.JSONObject(String(response.body))
                    val roots=json(request("/api/folders",null,null)).getJSONArray("folders")
                    val root=(0 until roots.length()).map {roots.getJSONObject(it)}.first {it.getString("kind")=="app"}
                    folderName="SDM-tab-close-${UUID.randomUUID()}"
                    val created=request("/api/folders/create",org.json.JSONObject().put("parent",root.getString("id")).put("name",folderName),null)
                    assertEquals(String(created.body),200,created.status)
                    val folder=json(created).getString("id")
                    val uploaded=request("/api/torrent/upload",null,f.bytes);assertEquals(202,uploaded.status)
                    val draft=json(uploaded).getString("id")
                    runBlocking {await("tab close metadata") {json(request("/api/torrent/draft/$draft",null,null)).optString("status")=="ready"}}
                    val submitted=request("/api/torrent/submit",org.json.JSONObject().put("id",draft).put("selected",org.json.JSONArray(listOf(f.contents.files.first().index))).put("seed",false).put("startNow",false).put("folder",folder),null)
                    assertEquals(String(submitted.body),200,submitted.status);id=json(submitted).getString("id")
                    assertEquals(200,request("/api/download/limit",org.json.JSONObject().put("id",id).put("unlimited",false).put("value","32").put("unit","KB/s"),null).status)
                }
                prepared.writeText(repo.get(id!!)!!.fileName)
                withTimeout(180000){while(repo.get(id!!)!!.downloadedBytes<65536)delay(100)}
                ready.writeText("ready")
                withTimeout(60000){while(!closing.exists())delay(100)}
                val offset=repo.get(id!!)!!.downloadedBytes
                assertEquals(DownloadState.DOWNLOADING,repo.get(id!!)!!.state)
                assertTrue(offset<f.first.size)
                await("torrent gains bytes after external tab closes") {repo.get(id!!)!!.downloadedBytes>offset}
                withTimeout(120000){while(repo.get(id!!)!!.state!=DownloadState.COMPLETED)delay(100)}
                val row=repo.get(id!!)!!;assertEquals(f.first.size.toLong(),row.downloadedBytes)
                val selected=f.contents.files.first();val published=TorrentStore(context).selection(id!!).published[selected.index]
                val bytes=if(published!=null)context.contentResolver.openInputStream(android.net.Uri.parse(published))!!.use {it.readBytes()} else TorrentPaths.resolve(File(row.destinationPath!!),selected.path).readBytes()
                assertArrayEquals(f.first,bytes)
                assertEquals(originals,repo.downloads.value.filter {it.id!=id})
                assertEquals(browsers,com.espitman.sdm.web.WebPanelRuntime.access.browsers.value)
                assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all)
            } finally {
                id?.let {owned ->
                    com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context,owned)
                    await("tab close fixture engine cleanup") {TorrentRuntime.telemetry.value[owned]==null}
                    withIsolatedWebCommands {request ->
                        val removed=request("/api/downloads/remove",org.json.JSONObject().put("ids",org.json.JSONArray(listOf(owned))).put("confirmed",true).put("deleteFiles",true),null)
                        assertEquals(String(removed.body),200,removed.status)
                        assertTrue(org.json.JSONObject(String(removed.body)).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
                    }
                }
                folderName?.let {File(AppSpecificDownloadsDirectory.from(context),it).deleteRecursively()}
                prepared.delete();closing.delete();ready.delete()
            }
        }
        assertEquals(originals,repo.downloads.value)
    }

    @Test fun externalChromeTorrentPausesOnCellularAndResumesOnWifi() = runBlocking {
        org.junit.Assume.assumeTrue(com.espitman.sdm.data.settings.SettingsRepository.get(context).settings.value.let {it.wifiOnly && it.autoResume})
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        check(originals.all {it.state in setOf(DownloadState.PAUSED,DownloadState.FAILED,DownloadState.COMPLETED,DownloadState.CANCELLED)})
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        val browsers=com.espitman.sdm.web.WebPanelRuntime.access.browsers.value
        val settings=context.getSharedPreferences("sdm_settings",0).all
        val prepared=File(context.externalCacheDir,"web-torrent-network-prepared")
        val closing=File(context.externalCacheDir,"web-torrent-network-confirmed")
        val ready=File(context.externalCacheDir,"web-torrent-network-ready")
        prepared.delete();closing.delete();ready.delete()
        var id:String?=null;var folderName:String?=null
        Fixture(privateTorrent=false).use {f ->
            f.ready()
            try {
                withIsolatedWebCommands {request ->
                    fun json(response:com.espitman.sdm.web.LanHttpServer.Response)=org.json.JSONObject(String(response.body))
                    val roots=json(request("/api/folders",null,null)).getJSONArray("folders")
                    val root=(0 until roots.length()).map {roots.getJSONObject(it)}.first {it.getString("kind")=="app"}
                    folderName="SDM-torrent-network-${UUID.randomUUID()}"
                    val created=request("/api/folders/create",org.json.JSONObject().put("parent",root.getString("id")).put("name",folderName),null)
                    assertEquals(String(created.body),200,created.status)
                    val folder=json(created).getString("id")
                    val uploaded=request("/api/torrent/upload",null,f.bytes);assertEquals(202,uploaded.status)
                    val draft=json(uploaded).getString("id")
                    runBlocking {await("tab close metadata") {json(request("/api/torrent/draft/$draft",null,null)).optString("status")=="ready"}}
                    val submitted=request("/api/torrent/submit",org.json.JSONObject().put("id",draft).put("selected",org.json.JSONArray(listOf(f.contents.files.first().index))).put("seed",false).put("startNow",false).put("folder",folder),null)
                    assertEquals(String(submitted.body),200,submitted.status);id=json(submitted).getString("id")
                    assertEquals(200,request("/api/download/limit",org.json.JSONObject().put("id",id).put("unlimited",false).put("value","32").put("unit","KB/s"),null).status)
                }
                prepared.writeText(repo.get(id!!)!!.fileName)
                withTimeout(180000){while(repo.get(id!!)!!.downloadedBytes<65536)delay(100)}
                ready.writeText("ready")
                withTimeout(45000){while(repo.get(id!!)!!.pauseCause!=com.espitman.sdm.domain.DownloadPauseCause.NETWORK_POLICY)delay(100)}
                val paused=repo.get(id!!)!!;assertEquals(DownloadState.PAUSED,paused.state)
                val offset=paused.downloadedBytes;assertTrue(offset in 1 until f.first.size.toLong())
                delay(1500);assertEquals(offset,repo.get(id!!)!!.downloadedBytes)
                File(context.externalCacheDir,"web-torrent-network-paused").writeText("paused")
                withTimeout(120000){while(repo.get(id!!)!!.state!=DownloadState.COMPLETED)delay(100)}
                val row=repo.get(id!!)!!;assertEquals(f.first.size.toLong(),row.downloadedBytes)
                val selected=f.contents.files.first();val published=TorrentStore(context).selection(id!!).published[selected.index]
                val bytes=if(published!=null)context.contentResolver.openInputStream(android.net.Uri.parse(published))!!.use {it.readBytes()} else TorrentPaths.resolve(File(row.destinationPath!!),selected.path).readBytes()
                assertArrayEquals(f.first,bytes)
                assertEquals(originals,repo.downloads.value.filter {it.id!=id})
                assertEquals(browsers,com.espitman.sdm.web.WebPanelRuntime.access.browsers.value)
                assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all)
            } finally {
                id?.let {owned ->
                    com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context,owned)
                    await("tab close fixture engine cleanup") {TorrentRuntime.telemetry.value[owned]==null}
                    withIsolatedWebCommands {request ->
                        val removed=request("/api/downloads/remove",org.json.JSONObject().put("ids",org.json.JSONArray(listOf(owned))).put("confirmed",true).put("deleteFiles",true),null)
                        assertEquals(String(removed.body),200,removed.status)
                        assertTrue(org.json.JSONObject(String(removed.body)).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
                    }
                }
                folderName?.let {File(AppSpecificDownloadsDirectory.from(context),it).deleteRecursively()}
                prepared.delete();closing.delete();ready.delete();File(context.externalCacheDir,"web-torrent-network-paused").delete()
            }
        }
        assertEquals(originals,repo.downloads.value)
    }

    /** Keeps an owned real peer available while external Chrome drives the actual panel. */
    @Test fun externalChromeMagnetFixture() = runBlocking { externalChromeFixture(false) }
    @Test fun externalChromeUploadFixture() = runBlocking { externalChromeFixture(true) }
    private suspend fun externalChromeFixture(upload: Boolean) {
        val repo=com.espitman.sdm.data.AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        check(originals.all { it.state in setOf(DownloadState.PAUSED,DownloadState.COMPLETED,DownloadState.FAILED,DownloadState.CANCELLED) })
        com.espitman.sdm.web.WebPanelRuntime.initialize(context)
        context.startActivity(android.content.Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        var owned:String?=null
        Fixture(privateTorrent=false).use { f ->
            f.ready()
            if (upload) {
                println("BROWSER_TORRENT_FILE="+File(f.base,"fixture.torrent").absolutePath)
                println("BROWSER_TORRENT_BASE64="+android.util.Base64.encodeToString(f.bytes, android.util.Base64.NO_WRAP))
            }
            else println("BROWSER_MAGNET_FIXTURE="+f.contents.magnet)
            try {
                withTimeout(600_000) {
                    while(owned==null) {
                        owned=repo.downloads.value.firstOrNull { row -> row.isTorrent && row.id !in originals.map { it.id } && TorrentMagnet.identities(row.url).intersect(TorrentMagnet.identities(f.contents.magnet)).isNotEmpty() }?.id
                        delay(200)
                    }
                    while(repo.get(owned!!)!!.state!=DownloadState.COMPLETED)delay(200)
                }
                val row=repo.get(owned!!)!!
                assertEquals(f.first.size.toLong(),row.totalBytes)
                val chosen=f.contents.files.first { it.path.endsWith("first.bin") }
                assertEquals(setOf(chosen.index),TorrentStore(context).selection(row.id).selected)
                val published=TorrentStore(context).selection(row.id).published[chosen.index]
                val bytes=if(published!=null)context.contentResolver.openInputStream(android.net.Uri.parse(published))!!.use { it.readBytes() } else TorrentPaths.resolve(File(row.destinationPath!!),chosen.path).readBytes()
                assertArrayEquals(f.first,bytes)
                assertEquals(originals,repo.downloads.value.filter { it.id!=owned })
                println("BROWSER_MAGNET_VERIFIED="+row.id)
                delay(20_000) // Allow capture of the completed real browser state before owned cleanup.
            } finally {
                owned?.let { id ->
                    com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context,id)
                    await("browser fixture engine stopped") { TorrentRuntime.telemetry.value[id]==null }
                    val access=com.espitman.sdm.web.WebPanelRuntime.access
                    val (pending,ticket)=access.request("Owned browser torrent cleanup","127.0.0.5")
                    check(access.approve(pending.id));val secret=checkNotNull(access.collect(pending.id,ticket));val browser=access.authorize(secret)
                    val bridge=com.espitman.sdm.web.WebAppBridge(context)
                    try {
                        val router=com.espitman.sdm.web.WebRouter(access,{"Fixture"},{null},bridge::handle)
                        val body=org.json.JSONObject().put("ids",org.json.JSONArray(listOf(id))).put("confirmed",true).put("deleteFiles",true)
                        val response=router.handle(com.espitman.sdm.web.LanHttpServer.Request("POST","/api/downloads/remove",mapOf("authorization" to "Bearer $secret","idempotency-key" to UUID.randomUUID().toString(),"content-type" to "application/json"),body.toString().toByteArray(),"127.0.0.5"))
                        assertEquals(String(response.body),200,response.status)
                        assertTrue(org.json.JSONObject(String(response.body)).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
                    } finally { bridge.close();access.revoke(browser.id) }
                }
            }
        }
        assertEquals(originals,repo.downloads.value)
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
