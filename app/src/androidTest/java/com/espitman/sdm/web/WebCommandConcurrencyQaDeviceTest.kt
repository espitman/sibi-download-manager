package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadQueueOrder
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Browser double-click/lost reply/native edits against only disposable QA records. */
@RunWith(AndroidJUnit4::class)
class WebCommandConcurrencyQaDeviceTest {
 @Test fun chromeCommandsCoalesceLostRepliesAndReconcileNativeEdits()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName.endsWith(".qa"))
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty())
  val records=(1..5).map{n->Download(url="https://example.test/command-$n",fileName=if(n==5)"Hidden-control.bin" else "SDM-concurrency-$n.bin",state=DownloadState.PAUSED,totalBytes=1000,downloadedBytes=n*10L,sortOrder=n.toLong(),createdAtEpochMillis=n.toLong())}
  records.forEach{repo.insert(it)}
  val access=WebPanelRuntime.access;val originalBrowsers=access.browsers.value
  val bridge=WebAppBridge(context);val stage=File(context.externalCacheDir,"web-command-stage");val count=AtomicInteger();var firstKey:String?=null
  val router=WebRouter(access,{"Isolated command QA"},{name->
   val mime=when{name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".js")->"text/javascript";name.endsWith(".css")->"text/css";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"};mime to context.assets.open(name).use{it.readBytes()}
  },bridge::handle)
  val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,{request->
   if(request.method=="POST"&&request.path=="/api/download/action"&&JSONObject(String(request.body)).getString("action")=="priority") {
    val number=count.incrementAndGet();val key=checkNotNull(request.headers["idempotency-key"])
    if(number==1)firstKey=key
    if(number==2)assertEquals("Manual retry must keep the lost-reply command key",firstKey,key)
    if(number==3)runBlocking{repo.delete(records[3].id)}
    val response=router.handle(request)
    when(number){
     1->{stage.writeText("lost-reply");Thread.sleep(1500);response.copy(body=byteArrayOf(),stream=object:java.io.InputStream(){override fun read():Int=throw java.io.IOException("Owned lost-reply fixture")},length=response.body.size.toLong())}
     2->{assertEquals(1,runBlocking{repo.get(records[0].id)}!!.priority);stage.writeText("lost-replayed");response}
     3->{val results=JSONObject(String(response.body)).getJSONArray("results");assertEquals(1,(0 until results.length()).count{results.getJSONObject(it).optBoolean("accepted")});stage.writeText("mixed-result");Thread.sleep(1500);response}
     else->error("Unexpected duplicate priority command")
    }
   } else router.handle(request)
  }).guardRequestsWith(router::preflight)
  try {
   stage.writeText("ready");server.start()
   withTimeout(120000){while(access.requests.value.isEmpty())delay(100)};access.approve(access.requests.value.single().id)
   withTimeout(300000){while(stage.readText().trim()!="prepare-reorder")delay(100)}
   assertEquals(3,count.get());assertEquals(1,repo.get(records[0].id)!!.priority);assertEquals(1,repo.get(records[2].id)!!.priority);assertNull(repo.get(records[3].id))
   repo.togglePriority(records[0].id,System.currentTimeMillis());repo.togglePriority(records[2].id,System.currentTimeMillis())
   stage.writeText("reorder-ready")
   val expected=listOf(records[1].id,records[2].id,records[0].id,records[4].id)
   withTimeout(120000){while(repo.downloads.value.sortedWith(DownloadQueueOrder.comparator).map{it.id}!=expected)delay(100)}
   for(record in records.filter{it.id!=records[3].id}) {val after=checkNotNull(repo.get(record.id));assertEquals(record.downloadedBytes,after.downloadedBytes);assertEquals(record.state,after.state);assertEquals(record.url,after.url)}
   stage.writeText("verified");delay(5000)
  } finally {server.close();bridge.close();records.forEach{repo.delete(it.id)};access.browsers.value.filter{browser->originalBrowsers.none{it.id==browser.id}}.forEach{access.revoke(it.id)};stage.delete()}
  assertTrue(repo.downloads.value.isEmpty());assertEquals(originalBrowsers,access.browsers.value)
 }
}
