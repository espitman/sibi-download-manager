package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.download.DownloadTransferService
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

/** Actual Chrome review gates, scoped to the disposable application and owned bytes. */
@RunWith(AndroidJUnit4::class)
class WebChromeLinksQaDeviceTest {
 @Test fun browserBulkPartialResultsAndRestart()=exercise(false)
 @Test fun browserExpiredReviewPreservesPartialFile()=exercise(true)
 private fun exercise(expiryOnly:Boolean)=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName.endsWith(".qa"))
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty())
  val directory=File(context.filesDir,"chrome-links-${UUID.randomUUID()}").apply{check(mkdir())}
  val payload=ByteArray(2*1024*1024){(it%251).toByte()};val changed=ByteArray(3*1024*1024){(it%239).toByte()}
  val origin=ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))
  val worker=Thread {
   while(!origin.isClosed)try{val client=origin.accept();Thread{try{client.use{socket->
    val reader=socket.getInputStream().bufferedReader();val line=reader.readLine()?:return@use;var range:String?=null
    while(true){val h=reader.readLine()?:break;if(h.isEmpty())break;if(h.startsWith("Range:",true))range=h.substringAfter(':').trim()}
    val path=line.split(' ')[1];val output=socket.getOutputStream()
    if(path.contains("missing")){output.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray());return@use}
    val bytes=if(path.contains("changed"))changed else payload
    val bounds=range?.removePrefix("bytes=")?.split('-');val start=bounds?.firstOrNull()?.toIntOrNull()?:0;val end=minOf(bounds?.getOrNull(1)?.toIntOrNull()?:bytes.lastIndex,bytes.lastIndex)
    val name=if(path.contains("first"))"SDM-link-first.bin" else "SDM-link-second.bin"
    output.write(("HTTP/1.1 ${if(range==null)"200 OK" else "206 Partial Content"}\r\nContent-Length: ${end-start+1}\r\nAccept-Ranges: bytes\r\nETag: \"${if(path.contains("changed"))"changed" else "same"}\"\r\nContent-Disposition: attachment; filename=\"$name\"\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n"+(if(range!=null)"Content-Range: bytes $start-$end/${bytes.size}\r\n" else "")+"\r\n").toByteArray())
    if(!line.startsWith("HEAD"))output.write(bytes,start,end-start+1)
   }}catch(_:Exception){}}.apply{isDaemon=true;start()}}catch(_:Exception){if(origin.isClosed)break}
  }.apply{isDaemon=true;start()}
  val base="http://127.0.0.1:${origin.localPort}"
  val records=listOf("first","second","expiry").mapIndexed{index,name->
   val destination=File(directory,"SDM-link-$name.bin");DownloadPartFile.forDestination(destination).writeBytes(payload.copyOf(65536))
   Download(url="$base/old-$name",fileName=destination.name,destinationPath=destination.path,state=DownloadState.PAUSED,totalBytes=payload.size.toLong(),downloadedBytes=65536,etag="\"same\"",acceptsRanges=true,createdAtEpochMillis=index.toLong()+1)
  }
  val access=WebPanelRuntime.access;val originals=access.browsers.value;val bridge=WebAppBridge(context)
  val stage=File(context.externalCacheDir,"web-links-stage");val info=File(context.externalCacheDir,"web-links-info.json")
  val router=WebRouter(access,{"Isolated link QA"},{name->val mime=when{name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".js")->"text/javascript";name.endsWith(".css")->"text/css";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"};mime to context.assets.open(name).use{it.readBytes()}},bridge::handle)
  val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,{request->
   if(request.path=="/api/download/link/bulk/apply"&&request.method=="POST")runBlocking {
    RandomAccessFile(DownloadPartFile.forDestination(File(records[1].destinationPath!!)),"rw").use{it.setLength(65535)}
    repo.alignDownloadedBytes(records[1].id,65535,System.currentTimeMillis())
   }
   val response=router.handle(request)
   if(request.path=="/api/download/link/check"&&expiryOnly&&response.status==200)stage.writeText("expires:${System.currentTimeMillis()+120000}")
   if(request.path=="/api/download/link/bulk/apply"){val results=JSONObject(String(response.body)).getJSONArray("results");assertEquals(1,(0 until results.length()).count{results.getJSONObject(it).optBoolean("ok")});stage.writeText("mixed")}
   response
  }).guardRequestsWith(router::preflight)
  try {
   records.forEach{repo.insert(it)};info.writeText(JSONObject().put("base",base).toString());stage.writeText("ready");server.start()
   withTimeout(180000){while(access.requests.value.isEmpty())delay(100)};access.approve(access.requests.value.single().id)
   withTimeout(600000){while(stage.readText().trim()!="done")delay(100)}
   if(!expiryOnly) {
   withTimeout(60000){while(records.take(2).any{repo.get(it.id)?.state!=DownloadState.COMPLETED})delay(100)}
   assertArrayEquals(payload,File(records[0].destinationPath!!).readBytes());assertArrayEquals(changed,File(records[1].destinationPath!!).readBytes())
   assertTrue(repo.get(records[0].id)!!.url.endsWith("new-first"));assertTrue(repo.get(records[1].id)!!.url.endsWith("changed-second"))
   } else records.take(2).forEach{assertEquals(it,repo.get(it.id))}
   assertEquals(records[2],repo.get(records[2].id));assertArrayEquals(payload.copyOf(65536),DownloadPartFile.forDestination(File(records[2].destinationPath!!)).readBytes())
   stage.writeText("verified");delay(1000)
  } finally {
   server.close();bridge.close();origin.close();worker.join(1000)
   records.forEach{DownloadTransferService.cancelTransfer(context,it.id);repo.delete(it.id)};directory.deleteRecursively();stage.delete();info.delete()
   access.browsers.value.filter{b->originals.none{it.id==b.id}}.forEach{access.revoke(it.id)}
  }
  assertTrue(repo.downloads.value.isEmpty());assertEquals(originals,access.browsers.value)
 }
}
