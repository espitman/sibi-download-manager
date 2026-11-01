package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DailyBulkSchedule
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.time.ZonedDateTime
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** Real wall-clock alarms and global active-transfer changes in an isolated app ID only. */
@RunWith(AndroidJUnit4::class)
class WebDailyExecutionQaDeviceTest {
 @Test fun dailyAlarmsAndLiveGlobalLimitsControlOnlyOwnedTransfers()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  check(context.packageName.endsWith(".qa")){"Global execution requires the isolated QA application"}
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty())
  val preferences=SettingsRepository.get(context);val original=preferences.settings.value
  val access=WebPanelRuntime.access;val pending=access.request("Owned global execution","127.0.0.7")
  check(access.approve(pending.first.id));val secret=checkNotNull(access.collect(pending.first.id,pending.second));val browser=access.authorize(secret)
  val bridge=WebAppBridge(context);val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
  fun request(path:String,body:JSONObject?=null):LanHttpServer.Response=router.handle(LanHttpServer.Request(if(body==null)"GET" else "POST",path,buildMap{put("authorization","Bearer $secret");if(body!=null){put("content-type","application/json");put("idempotency-key",UUID.randomUUID().toString())}},body?.toString()?.toByteArray()?:byteArrayOf(),"127.0.0.7"))
  fun json(path:String,body:JSONObject?=null):JSONObject {val response=request(path,body);assertEquals(String(response.body),200,response.status);return JSONObject(String(response.body))}
  fun settings(body:JSONObject){json("/api/settings",body)}
  val length=64L*1024*1024
  val clients=CopyOnWriteArrayList<Thread>();val sockets=CopyOnWriteArrayList<java.net.Socket>()
  val origin=ServerSocket(0,64,InetAddress.getByName("127.0.0.1"))
  val acceptor=Thread {
   while(!origin.isClosed)try {
    val client=origin.accept();sockets+=client
    clients+=Thread {
     try {client.use {
      client.soTimeout=5000;val reader=client.getInputStream().bufferedReader();val line=reader.readLine()?:return@use
      var range:String?=null
      while(true){val header=reader.readLine()?:break;if(header.isEmpty())break;if(header.startsWith("Range:",true))range=header.substringAfter(':').trim()}
      val bounds=range?.removePrefix("bytes=")?.split('-');val start=bounds?.firstOrNull()?.toLongOrNull()?:0L;val end=minOf(bounds?.getOrNull(1)?.toLongOrNull()?:length-1,length-1)
      val output=client.getOutputStream();output.write(("HTTP/1.1 ${if(range==null)"200 OK" else "206 Partial Content"}\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\nETag: \"global-v1\"\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n"+(if(range!=null)"Content-Range: bytes $start-$end/$length\r\n" else "")+"\r\n").toByteArray())
      if(!line.startsWith("HEAD")) {
       val block=ByteArray(65536){(start+it).toByte()};var offset=start
       while(offset<=end){val count=minOf(block.size.toLong(),end-offset+1).toInt();output.write(block,0,count);offset+=count}
       output.flush()
      }
     }}catch(_:Exception){}finally{sockets.remove(client)}
    }.apply{isDaemon=true;start()}
   }catch(_:Exception){if(origin.isClosed)break}
  }.apply{isDaemon=true;start()}
  val ids=mutableListOf<String>();var directory:File?=null
  fun total()=repo.downloads.value.filter{it.id in ids}.sumOf{it.downloadedBytes}
  suspend fun measuredRate(milliseconds:Long):Double {val before=total();val start=System.nanoTime();delay(milliseconds);return (total()-before)*1e9/(System.nanoTime()-start)}
  try {
   val roots=json("/api/folders").getJSONArray("folders");val root=(0 until roots.length()).map{roots.getJSONObject(it)}.first{it.getString("kind")=="app"}
   val name="SDM-global-${UUID.randomUUID()}";val folder=json("/api/folders/create",JSONObject().put("parent",root.getString("id")).put("name",name)).getString("id")
   directory=File(AppSpecificDownloadsDirectory.from(context),name)
   val added=json("/api/downloads/add",JSONObject().put("links",JSONArray(listOf("http://127.0.0.1:${origin.localPort}/first.bin","http://127.0.0.1:${origin.localPort}/second.bin"))).put("folder",folder).put("startNow",false))
   val results=added.getJSONArray("results");repeat(results.length()){ids+=results.getJSONObject(it).getString("id")};assertEquals(2,ids.size)
   val now=ZonedDateTime.now();val start=now.plusMinutes(if(now.second>45)2 else 1).withSecond(0).withNano(0);val end=start.plusMinutes(1)
   settings(JSONObject().put("connections",8).put("simultaneous",1).put("wifiOnly",false).put("unlimitedSpeed",false).put("speedLimitMbps",1).put("speedLimitWifiOnly",false).put("dailyBulkScheduleEnabled",true).put("dailyResumeMinute",start.hour*60+start.minute).put("dailyPauseMinute",end.hour*60+end.minute))
   println("QA_DAILY_WAITING start=${start.toInstant()} end=${end.toInstant()}")
   withTimeout(140000){while(total()<65536)delay(100)}
   assertEquals(1,repo.downloads.value.count{it.state==DownloadState.DOWNLOADING})
   val single=measuredRate(5000);assertTrue("Global 1 MB/s cap did not apply: $single",single in 400000.0..1350000.0)
   settings(JSONObject().put("simultaneous",2).put("connections",32).put("speedLimitWifiOnly",true))
   withTimeout(15000){while(repo.downloads.value.count{it.state==DownloadState.DOWNLOADING}!=2)delay(100)}
   val shared=measuredRate(5000);assertTrue("Global cap must be shared across active downloads: $shared",shared in 400000.0..1450000.0)
   settings(JSONObject().put("speedLimitMbps",2));val faster=measuredRate(5000)
   assertTrue("Active global limit change did not take effect: $faster",faster in 1400000.0..2800000.0)
   settings(JSONObject().put("speedLimitMbps",1))
   println("QA_GLOBAL_RATES single=$single shared=$shared changed=$faster")
   withTimeout(100000){while(repo.downloads.value.any{it.state!=DownloadState.PAUSED})delay(100)}
   val paused=repo.downloads.value.associate{it.id to it.downloadedBytes};assertTrue(paused.values.all{it in 1 until length})
   println("QA_DAILY_PAUSED_WITH_PROGRESS")
   settings(JSONObject().put("dailyBulkScheduleEnabled",false).put("unlimitedSpeed",true))
   json("/api/download/action",JSONObject().put("ids",JSONArray(ids)).put("action","resume"))
   withTimeout(60000){while(repo.downloads.value.any{it.state!=DownloadState.COMPLETED})delay(100)}
   val block=ByteArray(65536){it.toByte()};val expected=MessageDigest.getInstance("SHA-256");repeat(1024){expected.update(block)};val hash=expected.digest()
   for(id in ids){val response=request("/api/files/$id/main/download");assertEquals(200,response.status);val digest=MessageDigest.getInstance("SHA-256");checkNotNull(response.stream).use{stream->val buffer=ByteArray(65536);while(true){val read=stream.read(buffer);if(read<0)break;if(read>0)digest.update(buffer,0,read)}};assertArrayEquals(hash,digest.digest());assertTrue(repo.get(id)!!.downloadedBytes>=paused.getValue(id))}
  } finally {
   ids.forEach{DownloadTransferService.cancelTransfer(context,it);repo.delete(it)}
   preferences.update{original};DailyBulkSchedule.arm(context)
   origin.close();sockets.forEach{runCatching{it.close()}};acceptor.join(1000);clients.forEach{it.join(1000)}
   directory?.deleteRecursively();access.revoke(browser.id);bridge.close()
  }
  assertTrue(repo.downloads.value.isEmpty())
 }
}
