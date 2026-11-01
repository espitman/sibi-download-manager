package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadPauseCause
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.ServerSocket
import java.net.InetAddress
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(AndroidJUnit4::class)
class WebTabCloseHttpDeviceTest {
 @Test fun externalChromeTabCloseKeepsHttpServiceRunning() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  val access=WebPanelRuntime.access
  val originalBrowsers=access.browsers.value
  val originalSettings=context.getSharedPreferences("sdm_settings",0).all
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originals=repo.downloads.value
  val (pending,ticket)=access.request("Owned HTTP tab close fixture","127.0.0.7")
  check(access.approve(pending.id));val secret=checkNotNull(access.collect(pending.id,ticket));val browser=checkNotNull(access.authorize(secret))
  val bridge=WebAppBridge(context);val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
  fun request(path:String,body:JSONObject?=null)=router.handle(LanHttpServer.Request(if(body==null)"GET" else "POST",path,buildMap { put("authorization","Bearer $secret");if(body!=null){put("content-type","application/json");put("idempotency-key",UUID.randomUUID().toString())} },body?.toString()?.toByteArray()?:byteArrayOf(),"127.0.0.7"))
  fun json(response:LanHttpServer.Response)=JSONObject(String(response.body))
  val payload=ByteArray(2*1024*1024) { (it%251).toByte() }
  val slow=AtomicLong(0)
  val origin=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
  val worker=Thread {
   while(!origin.isClosed)try { val accepted=origin.accept();Thread { try { accepted.use { client ->
    client.soTimeout=3000;val reader=client.getInputStream().bufferedReader();val line=reader.readLine()?:return@use
    var range:String?=null
    while(true){val header=reader.readLine()?:break;if(header.isEmpty())break;if(header.startsWith("Range:",true))range=header.substringAfter(':').trim()}
    val bounds=range?.removePrefix("bytes=")?.split('-');val start=bounds?.firstOrNull()?.toIntOrNull()?:0;val end=minOf(bounds?.getOrNull(1)?.toIntOrNull()?:payload.lastIndex,payload.lastIndex)
    val output=client.getOutputStream();output.write(("HTTP/1.1 ${if(range==null)"200 OK" else "206 Partial Content"}\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\nETag: \"schedule-v1\"\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n"+(if(range!=null)"Content-Range: bytes $start-$end/${payload.size}\r\n" else "")+"\r\n").toByteArray())
    if(!line.startsWith("HEAD"))for(offset in start..end step 1024){output.write(payload,offset,minOf(1024,end-offset+1));output.flush();if(slow.get()>0)Thread.sleep(slow.get())}
   }} catch(_:Exception){} }.apply {isDaemon=true;start()} }catch(_:Exception){if(origin.isClosed)break}
  }.apply { isDaemon=true;start() }
  var id:String?=null;var directory:File?=null
  try {
   val roots=json(request("/api/folders")).getJSONArray("folders");val root=(0 until roots.length()).map {roots.getJSONObject(it)}.first {it.getString("kind")=="app"}
   val name="SDM-http-tab-close-${UUID.randomUUID()}";val folder=json(request("/api/folders/create",JSONObject().put("parent",root.getString("id")).put("name",name))).getString("id")
   directory=File(AppSpecificDownloadsDirectory.from(context),name)
   val prepared=File(context.externalCacheDir,"web-http-tab-close-prepared")
   val ready=File(context.externalCacheDir,"web-http-tab-close-ready")
   val closed=File(context.externalCacheDir,"web-http-tab-close-confirmed")
   prepared.delete();ready.delete();closed.delete()
   val body=JSONObject().put("links",JSONArray(listOf("http://127.0.0.1:${origin.localPort}/$name.bin"))).put("folder",folder).put("startNow",false)
   val added=request("/api/downloads/add",body);assertEquals(String(added.body),200,added.status)
   id=json(added).getJSONArray("results").getJSONObject(0).getString("id")
   assertEquals(200,request("/api/download/limit",JSONObject().put("id",id).put("unlimited",false).put("value","32").put("unit","KB/s")).status)
   prepared.writeText(repo.get(id!!)!!.fileName)
   withTimeout(180000){while(repo.get(id!!)!!.downloadedBytes<65536)delay(100)}
   ready.writeText("ready")
   withTimeout(60000){while(!closed.exists())delay(100)}
   val offset=repo.get(id!!)!!.downloadedBytes
   assertEquals(DownloadState.DOWNLOADING,repo.get(id!!)!!.state);assertTrue(offset<payload.size)
   withTimeout(30000){while(repo.get(id!!)!!.downloadedBytes<=offset)delay(100)}
   withTimeout(120000){while(repo.get(id!!)!!.state!=DownloadState.COMPLETED)delay(100)}
   val file=request("/api/files/$id/main/download");assertEquals(200,file.status);assertArrayEquals(payload,checkNotNull(file.stream).use {it.readBytes()})
   assertEquals(originals,repo.downloads.value.filter {it.id!=id});assertEquals(originalSettings,context.getSharedPreferences("sdm_settings",0).all)
   assertEquals(originalBrowsers,access.browsers.value.filter {it.id!=browser.id})
  } finally {
   id?.let { owned ->
    DownloadTransferService.cancelTransfer(context,owned)
    val removed=request("/api/downloads/remove",JSONObject().put("ids",JSONArray(listOf(owned))).put("confirmed",true).put("deleteFiles",true))
    assertEquals(String(removed.body),200,removed.status)
    assertTrue(json(removed).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
   }
   listOf("web-http-tab-close-prepared","web-http-tab-close-ready","web-http-tab-close-confirmed").forEach {File(context.externalCacheDir,it).delete()}
   origin.close();worker.join(3000);directory?.deleteRecursively();bridge.close();access.revoke(browser.id)
  }
 }
}
