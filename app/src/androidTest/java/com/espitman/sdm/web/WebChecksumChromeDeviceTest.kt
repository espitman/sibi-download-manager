package com.espitman.sdm.web

import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.SaveLocationStore
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebChecksumChromeDeviceTest {
 @Test fun externalChromeComparesManualSha256WithoutChangingRecords()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context);WebPanelRuntime.start(context)
  withTimeout(30000){while(!WebPanelRuntime.status.value.running)delay(100)}
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val original=repo.downloads.value
  val browsers=WebPanelRuntime.access.browsers.value;val settings=context.getSharedPreferences("sdm_settings",0).all.toMap()
  val directory=File(context.filesDir,"checksum-chrome-${UUID.randomUUID()}").apply{check(mkdir())}
  val file=File(directory,"SDM-checksum-fixture.bin").apply{writeBytes(ByteArray(32771){(it%251).toByte()})}
  val hash=MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)}
  val now=System.currentTimeMillis()
  val row=Download(url="https://example.test/checksum-fixture",fileName=file.name,destinationPath=file.path,totalBytes=file.length(),downloadedBytes=file.length(),state=DownloadState.COMPLETED,createdAtEpochMillis=now,completedAtEpochMillis=now)
  val ready=File(context.externalCacheDir,"web-checksum-chrome-ready");val done=File(context.externalCacheDir,"web-checksum-chrome-done")
  try {
   done.delete();repo.insert(row);ready.writeText(hash)
   withTimeout(300000){while(!done.exists())delay(100)}
   assertEquals(row,repo.get(row.id));assertEquals(hash,MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString(""){"%02x".format(it)})
  } finally {repo.delete(row.id);directory.deleteRecursively();ready.delete();done.delete()}
  assertTrue("Original records changed during Chrome checksum",original==repo.downloads.value)
  assertEquals(browsers,WebPanelRuntime.access.browsers.value);assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all.toMap())
 }
 @Test fun providerLargeFileMatchMismatchAndMissingAccessPreserveOriginals()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val original=repo.downloads.value
  val access=WebPanelRuntime.access;val browsers=access.browsers.value
  val pending=access.request("Owned provider checksum","127.0.0.7");check(access.approve(pending.first.id));val secret=checkNotNull(access.collect(pending.first.id,pending.second));val browser=access.authorize(secret)
  val bridge=WebAppBridge(context);val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
  val tree=Uri.parse(checkNotNull(SaveLocationStore.get(context).read().treeUri));val parent=DC.buildDocumentUriUsingTree(tree,DC.getTreeDocumentId(tree))
  val directory=checkNotNull(DC.createDocument(context.contentResolver,parent,DC.Document.MIME_TYPE_DIR,"SDM-checksum-${UUID.randomUUID()}"))
  val document=checkNotNull(DC.createDocument(context.contentResolver,directory,"application/octet-stream","large.bin"))
  val block=ByteArray(8192){(it%251).toByte()};val digest=MessageDigest.getInstance("SHA-256");val count=1536
  val ids=mutableListOf<String>()
  fun request(id:String,expected:String)=router.handle(LanHttpServer.Request("POST","/api/download/checksum",mapOf("authorization" to "Bearer $secret","content-type" to "application/json","idempotency-key" to UUID.randomUUID().toString()),JSONObject().put("id",id).put("expected",expected).toString().toByteArray(),"127.0.0.7"))
  fun result(id:String,expected:String):JSONObject {val response=request(id,expected);assertEquals(200,response.status);return JSONObject(String(response.body))}
  try {
   context.contentResolver.openOutputStream(document,"wt")!!.use{output->repeat(count){output.write(block);digest.update(block)}}
   val hash=digest.digest().joinToString(""){"%02x".format(it)};val now=System.currentTimeMillis();val length=count.toLong()*block.size
   val row=Download(url="https://example.test/provider-checksum",fileName="large.bin",destinationPath=document.toString(),destinationTreeUri=tree.toString(),totalBytes=length,downloadedBytes=length,state=DownloadState.COMPLETED,createdAtEpochMillis=now,completedAtEpochMillis=now)
   ids+=row.id;repo.insert(row)
   val match=result(row.id,hash);assertEquals("MATCH",match.getString("status"));assertEquals(hash,match.getString("actual"))
   assertEquals("MISMATCH",result(row.id,"0".repeat(64)).getString("status"));assertEquals(row,repo.get(row.id))
   val ungrantedTree=DC.buildTreeDocumentUri(document.authority,DC.getDocumentId(directory))
   val denied=row.copy(id=UUID.randomUUID().toString(),destinationPath=DC.buildDocumentUriUsingTree(ungrantedTree,DC.getDocumentId(document)).toString(),destinationTreeUri=ungrantedTree.toString())
   ids+=denied.id;repo.insert(denied)
   assertEquals("UNAVAILABLE",result(denied.id,hash).getString("status"))
   assertTrue(DC.deleteDocument(context.contentResolver,document));assertEquals("UNAVAILABLE",result(row.id,hash).getString("status"))
  } finally {ids.forEach{repo.delete(it)};DC.deleteDocument(context.contentResolver,directory);access.revoke(browser.id);bridge.close()}
  assertTrue("Original records changed during provider checksum",original==repo.downloads.value);assertEquals(browsers,access.browsers.value)
 }
}
