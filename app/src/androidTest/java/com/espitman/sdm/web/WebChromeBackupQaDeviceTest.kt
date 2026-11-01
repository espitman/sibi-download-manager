package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.download.DailyBulkSchedule
import com.espitman.sdm.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebChromeBackupQaDeviceTest {
 @Test fun chromeReviewInvalidReplacementAndRestoreSettingsKeepExistingBytes()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName.endsWith(".qa"))
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty())
  val preferences=SettingsRepository.get(context);val original=preferences.settings.value
  val categories=CategoryFolderStore.get(context);val originalCategories=categories.settings.value
  val access=WebPanelRuntime.access;val browsers=access.browsers.value
  val directory=File(context.filesDir,"chrome-backup-${UUID.randomUUID()}").apply{check(mkdir())}
  val destination=File(directory,"existing.bin");val bytes=ByteArray(32){it.toByte()};val part=DownloadPartFile.forDestination(destination).apply{writeBytes(bytes)}
  val existing=Download(url="https://example.test/existing-${UUID.randomUUID()}",fileName="existing.bin",destinationPath=destination.path,state=DownloadState.PAUSED,totalBytes=100,downloadedBytes=32,createdAtEpochMillis=1)
  val candidate=Download(url="https://example.test/restored-${UUID.randomUUID()}",fileName="restored-from-browser.bin",state=DownloadState.PAUSED,speedLimitBytesPerSecond=300000,createdAtEpochMillis=2)
  val settings=original.copy(connections=32,theme="light",dailyBulkScheduleEnabled=false)
  val rules=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to CategoryFolderRule("content://fixture/tree/video","QA video folder")))
  val backup=File(context.externalCacheDir,"web-chrome-completion-backup.json")
  val done=File(context.externalCacheDir,"web-chrome-backup-done")
  val bridge=WebAppBridge(context)
  val router=WebRouter(access,{"Isolated backup QA"},{name->val mime=when{name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".js")->"text/javascript";name.endsWith(".css")->"text/css";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"};mime to context.assets.open(name).use{it.readBytes()}},bridge::handle)
  val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,router::handle).guardRequestsWith(router::preflight)
  try {
   repo.insert(existing);backup.writeText(DownloadBackupCodec.encode(listOf(existing,existing,candidate),settings,System.currentTimeMillis(),rules));done.delete();server.start()
   withTimeout(120000){while(access.requests.value.isEmpty())delay(100)};access.approve(access.requests.value.single().id)
   withTimeout(300000){while(!done.exists())delay(100)}
   assertEquals(existing,repo.get(existing.id));assertArrayEquals(bytes,part.readBytes())
   val restored=repo.downloads.value.single{it.url==candidate.url};assertEquals(DownloadState.PAUSED,restored.state);assertEquals(0L,restored.downloadedBytes);assertEquals(300000L,restored.speedLimitBytesPerSecond)
   assertEquals(2,repo.downloads.value.size);assertEquals(settings,preferences.settings.value);assertEquals(rules,categories.settings.value);assertTrue(FileCategory.VIDEO in categories.unavailable.value)
  } finally {
   server.close();bridge.close();repo.downloads.value.forEach{row->row.destinationPath?.let{DownloadPartFile.forDestination(File(it)).delete()};repo.delete(row.id)}
   preferences.update{original};categories.replace(originalCategories);DailyBulkSchedule.arm(context)
   directory.deleteRecursively();backup.delete();done.delete()
   access.browsers.value.filter{browser->browsers.none{it.id==browser.id}}.forEach{access.revoke(it.id)}
  }
  assertTrue(repo.downloads.value.isEmpty());assertEquals(browsers,access.browsers.value)
 }
}
