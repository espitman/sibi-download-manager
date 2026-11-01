package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadPartFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebDownloadRenameDeviceTest {
    @Test fun pausedRenamePreservesPartialBytesAndRejectsCollisionAndActiveTransfer() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending,ticket) = access.request("Owned rename fixture", "127.0.0.6")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id,ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access,{"Fixture"},{null},bridge::handle)
        val repo = AppRepositories.downloads(context); repo.awaitInitialized()
        val originals = repo.downloads.value
        val directory = File(context.filesDir,"web-rename-${UUID.randomUUID()}").apply { check(mkdir()) }
        val id = UUID.randomUUID().toString()
        val source = File(directory,"original.bin")
        val origin = java.net.ServerSocket(0,8,java.net.InetAddress.getByName("127.0.0.1"))
        val payload=ByteArray(100) { (it+1).toByte() }
        val requestedRanges=java.util.concurrent.CopyOnWriteArrayList<String>()
        val worker=Thread {
            while(!origin.isClosed) try { origin.accept().use { client ->
                client.soTimeout=3000
                val reader=client.getInputStream().bufferedReader();val line=reader.readLine().orEmpty()
                var range:String?=null
                while(true){val header=reader.readLine()?:break;if(header.isEmpty())break;if(header.startsWith("Range:",true))range=header.substringAfter(':').trim()}
                val start=range?.substringAfter("bytes=")?.substringBefore('-')?.toInt()?:0
                if(range!=null)requestedRanges.add(range!!)
                val end=range?.substringAfter('-')?.takeIf { it.isNotBlank() }?.toInt()?:99
                val partial=range!=null
                val header="HTTP/1.1 ${if(partial) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${end-start+1}\r\nETag: \"fixture\"\r\nAccept-Ranges: bytes\r\n"+(if(partial)"Content-Range: bytes $start-$end/100\r\n" else "")+"Connection: close\r\n\r\n"
                client.getOutputStream().write(header.toByteArray())
                if(!line.startsWith("HEAD"))client.getOutputStream().write(payload.copyOfRange(start,end+1))
            }}catch(_:Exception){if(origin.isClosed)break}
        }.apply { isDaemon=true;start() }
        val bytes = byteArrayOf(1,2,3,4,5)
        val part = DownloadPartFile.forDestination(source).apply { writeBytes(bytes) }
        val collision = File(directory,"occupied.bin").apply { writeBytes(byteArrayOf(8,9)) }
        fun rename(name:String,key:String=UUID.randomUUID().toString()):LanHttpServer.Response {
            val body=JSONObject().put("id",id).put("name",name).toString().toByteArray()
            return router.handle(LanHttpServer.Request("POST","/api/downloads/rename",mapOf("authorization" to "Bearer $secret","content-type" to "application/json","idempotency-key" to key),body,"127.0.0.6"))
        }
        try {
            repo.insert(Download(id=id,url="http://127.0.0.1:${origin.localPort}/original.bin",etag="\"fixture\"",acceptsRanges=true,fileName=source.name,destinationPath=source.path,totalBytes=100,downloadedBytes=5,state=DownloadState.PAUSED,createdAtEpochMillis=System.currentTimeMillis()))
            assertEquals(409,rename(collision.name).status)
            assertEquals(409,rename("../escape.bin").status)
            assertArrayEquals(bytes,part.readBytes())
            val key=UUID.randomUUID().toString(); val response=rename("renamed.bin",key)
            assertEquals(String(response.body),200,response.status)
            assertEquals(response.body.toList(),rename("renamed.bin",key).body.toList())
            val updated=repo.get(id)!!
            assertEquals(DownloadState.PAUSED,updated.state);assertEquals(5L,updated.downloadedBytes)
            assertEquals(File(directory,"renamed.bin").path,updated.destinationPath)
            assertFalse(part.exists());assertArrayEquals(bytes,DownloadPartFile.forDestination(File(updated.destinationPath!!)).readBytes())
            repo.transition(id,DownloadState.QUEUED,System.currentTimeMillis())
            repo.transition(id,DownloadState.CONNECTING,System.currentTimeMillis())
            assertEquals(409,rename("active.bin").status)
            assertEquals(updated.destinationPath,repo.get(id)!!.destinationPath)
            repo.transition(id,DownloadState.PAUSED,System.currentTimeMillis())
            repo.transition(id,DownloadState.QUEUED,System.currentTimeMillis())
            com.espitman.sdm.download.DownloadTransferEngine(segmentCount={1}).executeTransfer(id,repo.get(id)!!.url,DownloadPartFile.forDestination(File(updated.destinationPath!!)),repo)
            val complete=repo.get(id)!!
            assertEquals(complete.error,DownloadState.COMPLETED,complete.state)
            assertEquals(100L,complete.downloadedBytes)
            assertArrayEquals(payload,File(complete.destinationPath!!).readBytes())
            assertTrue(requestedRanges.any { it=="bytes=5-" })
            assertArrayEquals(byteArrayOf(8,9),collision.readBytes())
            assertEquals(originals,repo.downloads.value.filter { it.id!=id })
        } finally {
            origin.close();worker.join(3000);repo.delete(id);directory.deleteRecursively();bridge.close();access.revoke(browser.id)
        }
    }
}
