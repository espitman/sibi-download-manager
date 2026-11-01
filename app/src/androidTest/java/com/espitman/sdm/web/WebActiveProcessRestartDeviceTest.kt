package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferService
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Host keeps a deterministic HTTP origin alive across actual application process death. */
@RunWith(AndroidJUnit4::class)
class WebActiveProcessRestartDeviceTest {
 @Test fun externalChromeHttpRecoveryAcrossProcessDeath() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  val manifest=File(context.noBackupFilesDir,"web-active-process-qa.json")
  fun digest(value:String)=MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
  fun records(exclude:String?=null)=digest(repo.downloads.value.filter { it.id!=exclude }.sortedBy { it.id }.toString())
  fun approvals()=digest(WebPanelRuntime.access.browsers.value.sortedBy { it.id }.toString())
  fun settings()=digest(context.getSharedPreferences("sdm_settings",0).all.toSortedMap().toString())
  when(InstrumentationRegistry.getArguments().getString("restartPhase")) {
   "prepare" -> {
    check(!manifest.exists()) { "Previous owned fixture must be cleaned first" }
    assertTrue(repo.downloads.value.none { it.state in setOf(DownloadState.DOWNLOADING,DownloadState.CONNECTING,DownloadState.QUEUED) })
    val id=UUID.randomUUID().toString()
    val directory=File(context.filesDir,"web-process-$id").apply { check(mkdir()) }
    val destination=File(directory,"sdm-process-$id.bin")
    val before=JSONObject().put("id",id).put("path",destination.path).put("records",records()).put("approvals",approvals()).put("settings",settings())
    manifest.writeText(before.toString())
    repo.insert(Download(id=id,url="http://127.0.0.1:18082/process-fixture.bin",fileName=destination.name,destinationPath=destination.path,totalBytes=8L*1024*1024,etag="\"sdm-process-fixture-v1\"",acceptsRanges=true,state=DownloadState.PAUSED,speedLimitBytesPerSecond=128L*1024,createdAtEpochMillis=System.currentTimeMillis()))
    println("CHROME_PROCESS_HTTP_READY=${destination.name}")
   }
   "verify", "cleanup" -> {
    check(manifest.isFile)
    val before=JSONObject(manifest.readText());val id=before.getString("id");val destination=File(before.getString("path"))
    try {
     if(InstrumentationRegistry.getArguments().getString("restartPhase")=="verify") {
      val complete=checkNotNull(repo.get(id));assertEquals(complete.error,DownloadState.COMPLETED,complete.state)
      assertEquals(8L*1024*1024,complete.downloadedBytes)
      assertArrayEquals(ByteArray(8*1024*1024) { (it%251).toByte() },destination.readBytes())
      assertEquals(before.getString("records"),records(id));assertEquals(before.getString("approvals"),approvals());assertEquals(before.getString("settings"),settings())
     }
    } finally {
     DownloadTransferService.cancelTransfer(context,id)
     repo.delete(id);destination.parentFile!!.deleteRecursively();manifest.delete()
    }
   }
   else -> fail("restartPhase must be prepare, verify or cleanup")
  }
 }
}
