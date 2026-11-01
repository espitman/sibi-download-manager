package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress

/** Production UI/assets on the phone, with read-only example data for design comparison. */
@RunWith(AndroidJUnit4::class)
class WebBoardVisualDeviceTest {
 @Test fun compareBoardsWithoutChangingOriginalDownloads()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originals=repo.downloads.value
  val settings=context.getSharedPreferences("sdm_settings",0).all.toMap()
  val browsers=WebPanelRuntime.access.browsers.value
  val access=WebAccess();val bridge=WebAppBridge(context)
  val stage=File(context.externalCacheDir,"web-board-stage");val done=File(context.externalCacheDir,"web-board-done")
  val router=WebRouter(access,{"SDM design fixture"},{name ->
   val mime=when {name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".js")->"text/javascript";name.endsWith(".css")->"text/css";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"}
   mime to context.assets.open(name).use{it.readBytes()}
  },{request,browser ->
   when {
    request.method=="GET"&&request.path=="/api/state" -> {
     val response=bridge.handle(request,browser);val state=JSONObject(String(response.body))
     val mode=stage.takeIf{it.exists()}?.readText()?.trim()?:"mixed"
     if(mode=="error")WebRouter.json(503,JSONObject().put("error","Example connection interrupted"))
     else {if(mode=="loading")Thread.sleep(3000)
      state.put("device","SDM design fixture").put("downloads",if(mode=="empty")JSONArray()else exampleRows(mode))
      WebRouter.json(value=state)
     }
    }
    request.method=="GET"&&request.path=="/api/torrent/details/visual-torrent" -> WebRouter.json(value=JSONObject()
     .put("id","visual-torrent").put("name","Open movies pack").put("hash","0123456789abcdef0123456789abcdef01234567")
     .put("magnet","magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567").put("total",3000000000L).put("seed",true)
     .put("trackers",JSONArray(listOf("udp://tracker.example.test:1337"))).put("files",JSONArray((0..2).map{index->JSONObject().put("index",index).put("path","Video ${index+1}.mp4").put("size",1000000000L).put("selected",index<2)})))
    request.method=="POST"&&request.path=="/api/download/checksum" -> {
     val expected=WebRouter.body(request).optString("expected");val actual="a".repeat(64)
     WebRouter.json(value=JSONObject().put("status",if(expected==actual)"MATCH" else "MISMATCH").put("message",if(expected==actual)"Checksum verified" else "Checksum mismatch").put("algorithm","SHA-256").put("expected",expected).put("actual",actual))
    }
    request.method=="GET"&&request.path=="/api/files" -> WebRouter.json(value=JSONObject().put("location","SDM").put("storage",JSONObject().put("total",128000000000L).put("available",64000000000L).put("used",64000000000L))
     .put("files",JSONArray(listOf("Research paper.pdf","Archive.zip","Notes.txt","Preview.png").mapIndexed{index,name->JSONObject().put("id","visual-file-$index").put("index","main").put("name",name).put("size",2400000).put("mime",if(index==0)"application/pdf" else  if(index==3)"image/png" else "application/octet-stream").put("preview",index==0||index==3).put("category","Other").put("modifiedAt",1793180000000L).put("completedAt",1793180000000L).put("available",true).put("renameAllowed",true).put("torrent",false)})))
    request.method=="GET"&&request.path.startsWith("/api/settings/") -> bridge.handle(request,browser)
    else -> WebRouter.json(405,JSONObject().put("error","Read-only visual fixture; original phone data is unchanged"))
   }
  })
  val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,router::handle).guardRequestsWith(router::preflight)
  try {
   done.delete();stage.writeText("mixed");server.start()
   withTimeout(120000){while(access.requests.value.isEmpty())delay(100)}
   access.approve(access.requests.value.single().id)
   var revoked=false;var limited=false
   withTimeout(1800000){while(!done.exists()) {
    val mode=stage.readText().trim()
    if(mode=="revoked"&&!revoked){access.browsers.value.forEach{access.revoke(it.id)};revoked=true}
    if(mode=="reapprove")access.requests.value.forEach{access.approve(it.id)}
    if(mode=="rate"&&!limited){
     access.browsers.value.forEach{access.revoke(it.id)};access.cancelPairing()
     repeat(5){val pending=access.request("Owned rate-limit fixture","127.0.0.1");access.deny(pending.first.id)}
     limited=true
    }
    delay(200)
   }}
   assertTrue("Original records changed during design comparison",originals==repo.downloads.value)
   assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all.toMap())
   assertEquals(browsers,WebPanelRuntime.access.browsers.value)
  } finally {server.close();bridge.close();stage.delete();done.delete()}
 }
 private fun exampleRows(mode:String):JSONArray {
  fun row(id:String,name:String,status:String,downloaded:Long,total:Long,rate:Long,torrent:Boolean=false)=JSONObject()
   .put("id",id).put("name",name).put("state",status).put("downloaded",downloaded).put("total",total).put("speed",rate)
   .put("connections",if(status=="DOWNLOADING")16 else 0).put("torrent",torrent).put("peers",if(torrent)24 else 0).put("seeds",if(torrent)8 else 0)
   .put("uploadRate",if(torrent)120000 else 0).put("seeding",mode=="seeding"&&torrent).put("uploaded",150000000).put("ratio",0.14)
   .put("fileProgress",JSONObject().put("0",1000000000L).put("1",360000000L)).put("trackers",JSONArray())
   .put("peerDetails",JSONArray()).put("checking",false).put("priority",0).put("order",if(torrent)2 else 1).put("createdAt",1793180000000L)
   .put("technical",JSONObject().put("source","example.test").put("savePath","Download/SDM").put("security","HTTPS").put("resume","Supported (Accept-Ranges)").put("headersAvailable",false).put("segmentsAvailable",false))
  return JSONArray().put(row("visual-http","Ubuntu-24.04.iso",if(mode=="paused")"PAUSED" else if(mode=="completed")"COMPLETED" else "DOWNLOADING",if(mode=="completed")5000000000 else 2100000000,5000000000,if(mode=="paused"||mode=="completed")0 else 740000))
   .put(row("visual-paused","Assets.zip","PAUSED",260000000,1200000000,0))
   .put(row("visual-torrent","Open movies pack",if(mode=="seeding")"COMPLETED" else "DOWNLOADING",if(mode=="seeding")3000000000 else 2040000000,3000000000,if(mode=="seeding")0 else 3100000,true))
   .put(row("visual-failed","Document.pdf","FAILED",0,8000000,0).put("error","Not enough space on phone"))
 }
}
