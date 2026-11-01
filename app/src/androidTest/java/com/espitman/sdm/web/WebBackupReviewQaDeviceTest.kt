package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.domain.Download
import com.espitman.sdm.download.DownloadPartFile
import com.espitman.sdm.storage.FolderMutationGate
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebBackupReviewQaDeviceTest {
 @Test fun revokedReviewCannotRestoreAfterWaitingForFolderMutation()=blockedReview(false)
 @Test fun expiredReviewCannotRestoreAfterWaitingForFolderMutation()=blockedReview(true)
 private fun blockedReview(expired:Boolean)=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  check(context.packageName.endsWith(".qa"))
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originals=repo.downloads.value
  val access=WebPanelRuntime.access;val pending=access.request("Owned backup review","127.0.0.7")
  check(access.approve(pending.first.id));val secret=checkNotNull(access.collect(pending.first.id,pending.second));val browser=access.authorize(secret)
  val coordinator=AppRepositories.archiveCoordinator(context)
  val clock=java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis())
  val backups=WebBackups(context,now=clock::get,archives={coordinator});val url="https://example.test/backup-revoked-${UUID.randomUUID()}"
  val candidate=Download(url=url,fileName="owned-backup.bin",createdAtEpochMillis=1)
  val review=backups.review(browser.id,DownloadBackupCodec.encode(listOf(candidate),null,1).toByteArray()).getString("review")
  val field=coordinator.javaClass.getDeclaredField("mutex").apply{isAccessible=true};val mutation=field.get(coordinator) as kotlinx.coroutines.sync.Mutex
  var held=false;var operation:Deferred<Result<org.json.JSONObject>>?=null
  try {
   FolderMutationGate.mutex.lock();held=true
   operation=async(Dispatchers.Default){runCatching{backups.restore(browser.id,review,false,true)}}
   withTimeout(10000){while(!mutation.isLocked)delay(10)}
   if(expired)clock.addAndGet(120001) else access.revoke(browser.id)
   FolderMutationGate.mutex.unlock();held=false
   val result=withTimeout(10000){operation!!.await()}
   assertTrue("Backup expiry/approval must be rechecked after the mutation wait",result.isFailure)
   assertTrue("Invalid backup review inserted records",originals==repo.downloads.value)
  } finally {
   if(held)FolderMutationGate.mutex.unlock();operation?.cancelAndJoin()
   repo.downloads.value.filter{it.url==url}.forEach{row->row.destinationPath?.let{DownloadPartFile.forDestination(File(it)).delete()};repo.delete(row.id)}
   access.revoke(browser.id)
  }
 }
 @Test fun foreignCancelledExpiredAndUnconfirmedReviewsKeepSettingsAndRecords()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName.endsWith(".qa"))
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originals=repo.downloads.value
  val settings=com.espitman.sdm.data.settings.SettingsRepository.get(context);val originalSettings=settings.settings.value
  val categories=com.espitman.sdm.storage.CategoryFolderStore.get(context);val originalCategories=categories.settings.value
  val access=WebPanelRuntime.access
  fun approve(address:String):WebAccess.Browser {val request=access.request("Owned negative backup",address);check(access.approve(request.first.id));return access.authorize(checkNotNull(access.collect(request.first.id,request.second)))}
  val owner=approve("127.0.0.8");val foreign=approve("127.0.0.9")
  val clock=java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());val backups=WebBackups(context,now=clock::get)
  val candidate=Download(url="https://example.test/negative-backup-${UUID.randomUUID()}",fileName="never-restored.bin",createdAtEpochMillis=1)
  val bytes=DownloadBackupCodec.encode(listOf(candidate),originalSettings.copy(theme="light",connections=32),1).toByteArray()
  try {
   assertTrue(runCatching{backups.review(owner.id,"not JSON".toByteArray())}.isFailure)
   val first=backups.review(owner.id,bytes).getString("review")
   assertTrue(runCatching{backups.restore(foreign.id,first,true,true)}.isFailure)
   assertTrue(runCatching{backups.restore(owner.id,first,true,false)}.isFailure)
   clock.addAndGet(120001)
   assertTrue(runCatching{backups.restore(owner.id,first,true,true)}.isFailure)
   backups.expire(access.browsers.value.map{it.id}.toSet())
   val second=backups.review(owner.id,bytes).getString("review");backups.cancel(owner.id,second)
   assertTrue(runCatching{backups.restore(owner.id,second,true,true)}.isFailure)
   assertTrue("Rejected backup changed records",originals==repo.downloads.value)
   assertEquals(originalSettings,settings.settings.value);assertEquals(originalCategories,categories.settings.value)
  } finally {access.revoke(owner.id);access.revoke(foreign.id)}
 }
}
