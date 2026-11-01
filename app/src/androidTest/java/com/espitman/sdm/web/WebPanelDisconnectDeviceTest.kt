package com.espitman.sdm.web

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Interrupts only the panel listener; all existing records must remain identical. */
@RunWith(AndroidJUnit4::class)
class WebPanelDisconnectDeviceTest {
    @Test fun disconnectAndRestorePreservesRecordsAndApprovals() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        assumeTrue(WebPanelRuntime.enabled(context))
        val repository=AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original=repository.downloads.value
        val approvals=WebPanelRuntime.access.browsers.value.map { it.id }.toSet()
        val port=WebPanelRuntime.port(context)
        context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            WebPanelRuntime.stop(context)
            withTimeout(10_000) { while(WebPanelRuntime.status.value.running)delay(100) }
            println("PANEL_DISCONNECTED")
            delay(20_000)
            assertFalse("Periodic network reconciliation must not restart an explicitly stopped panel", WebPanelRuntime.status.value.running)
            assertFalse(WebPanelRuntime.enabled(context))
            assertEquals(original,repository.downloads.value)
            assertEquals(approvals,WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
        } finally {
            WebPanelRuntime.start(context,port)
            withTimeout(10_000) { while(!WebPanelRuntime.status.value.running)delay(100) }
        }
        assertEquals(port,WebPanelRuntime.status.value.port)
        assertEquals(original,repository.downloads.value)
        assertEquals(approvals,WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
    }
    @Test fun listenerOutageDoesNotInterruptActiveHttpTransfer() = activeHttpOutage(false)
    @Test fun webResumeServiceSurvivesListenerOutage() = activeHttpOutage(true)
    private fun activeHttpOutage(service: Boolean) = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        assumeTrue(WebPanelRuntime.enabled(context))
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val originals=repo.downloads.value
        val approvals=WebPanelRuntime.access.browsers.value.map { it.id }.toSet()
        val port=WebPanelRuntime.port(context)
        val origin=java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val payload=ByteArray(1024*1024) { (it%251).toByte() }
        val directory=java.io.File(context.filesDir,"panel-active-${java.util.UUID.randomUUID()}").apply { check(mkdir()) }
        val destination=java.io.File(directory,"SDM-panel-active-http.bin")
        val id=java.util.UUID.randomUUID().toString()
        val worker=Thread {
            try { origin.accept().use { client ->
                client.soTimeout=3000
                val reader=client.getInputStream().bufferedReader();reader.readLine()
                while(!reader.readLine().isNullOrEmpty()){}
                val output=client.getOutputStream()
                output.write("HTTP/1.1 200 OK\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n".toByteArray())
                for(offset in payload.indices step 16384){output.write(payload,offset,minOf(16384,payload.size-offset));output.flush();Thread.sleep(150)}
            }}catch(_:Exception){}
        }.apply {isDaemon=true;start()}
        var transfer:Deferred<Unit>?=null
        var fixtureBrowser:WebAccess.Browser?=null
        var bridge:WebAppBridge?=null
        try {
            val url="http://127.0.0.1:${origin.localPort}/fixture.bin"
            repo.insert(com.espitman.sdm.domain.Download(id=id,url=url,fileName=destination.name,destinationPath=destination.path,totalBytes=payload.size.toLong(),acceptsRanges=false,state=if(service)com.espitman.sdm.domain.DownloadState.PAUSED else com.espitman.sdm.domain.DownloadState.QUEUED,createdAtEpochMillis=System.currentTimeMillis()))
            if(service){
                val access=WebPanelRuntime.access
                val (pending,ticket)=access.request("Owned active HTTP service fixture","127.0.0.7")
                check(access.approve(pending.id));val secret=checkNotNull(access.collect(pending.id,ticket))
                fixtureBrowser=access.authorize(secret);bridge=WebAppBridge(context)
                val router=WebRouter(access,{"Fixture"},{null},bridge!!::handle)
                val body=org.json.JSONObject().put("ids",org.json.JSONArray(listOf(id))).put("action","resume")
                val response=router.handle(LanHttpServer.Request("POST","/api/download/action",mapOf("authorization" to "Bearer $secret","content-type" to "application/json","idempotency-key" to java.util.UUID.randomUUID().toString()),body.toString().toByteArray(),"127.0.0.7"))
                assertEquals(String(response.body),200,response.status)
                assertTrue(org.json.JSONObject(String(response.body)).getJSONArray("results").getJSONObject(0).getBoolean("accepted"))
                transfer=async(Dispatchers.IO){withTimeout(30_000){while(repo.get(id)!!.state!=com.espitman.sdm.domain.DownloadState.COMPLETED)delay(50)}}
            }else transfer=async(Dispatchers.IO){com.espitman.sdm.download.DownloadTransferEngine(segmentCount={1},progressUpdateIntervalBytes=1024).executeTransfer(id,url,com.espitman.sdm.download.DownloadPartFile.forDestination(destination),repo)}
            withTimeout(10_000){while((repo.get(id)?.downloadedBytes?:0)<32768)delay(50)}
            val before=repo.get(id)!!.downloadedBytes
            WebPanelRuntime.stop(context)
            withTimeout(10_000){while(WebPanelRuntime.status.value.running)delay(50)}
            println("ACTIVE_HTTP_PANEL_DISCONNECTED")
            delay(4000)
            assertTrue(repo.get(id)!!.downloadedBytes>before)
            assertEquals(com.espitman.sdm.domain.DownloadState.DOWNLOADING,repo.get(id)!!.state)
            assertEquals(approvals,WebPanelRuntime.access.browsers.value.filter {it.id!=fixtureBrowser?.id}.map {it.id}.toSet())
            WebPanelRuntime.start(context,port)
            withTimeout(10_000){while(!WebPanelRuntime.status.value.running)delay(50)}
            withTimeout(30_000){transfer.await()}
            val complete=repo.get(id)!!
            assertEquals(complete.error,com.espitman.sdm.domain.DownloadState.COMPLETED,complete.state)
            assertArrayEquals(payload,destination.readBytes())
            assertEquals(originals,repo.downloads.value.filter {it.id!=id})
            assertEquals(approvals,WebPanelRuntime.access.browsers.value.filter {it.id!=fixtureBrowser?.id}.map {it.id}.toSet())
        } finally {
            transfer?.cancelAndJoin();origin.close();worker.join(3000)
            if(service){com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context,id);withTimeout(10_000){while(com.espitman.sdm.download.HttpConnectionTelemetry.active.value[id]!=null)delay(50)}}
            bridge?.close();fixtureBrowser?.let {WebPanelRuntime.access.revoke(it.id)}
            repo.delete(id);directory.deleteRecursively()
            WebPanelRuntime.start(context,port)
            withTimeout(10_000){while(!WebPanelRuntime.status.value.running)delay(50)}
        }
    }

}
