package com.espitman.sdm.web

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadSchedule
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/** Passive assertion fixture: all setting changes must originate in actual external Chrome. */
@RunWith(AndroidJUnit4::class)
class WebChromeOptionsDeviceTest {
 @Test fun externalChromeAppliesAndClearsScheduleAndLimit() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  if(WebPanelRuntime.enabled(context))WebPanelRuntime.start(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  val originals=repo.downloads.value
  val browsers=WebPanelRuntime.access.browsers.value
  val settings=context.getSharedPreferences("sdm_settings",0).all
  val id=UUID.randomUUID().toString()
  val name="sdm-options-$id.bin"
  val zone=ZoneId.systemDefault()
  val date=LocalDate.now(zone).plusDays(1)
  val expected=date.atTime(22,0).atZone(zone).toInstant().toEpochMilli()
  try {
   repo.insert(Download(id=id,url="https://example.invalid/owned-options.bin",fileName=name,state=DownloadState.PAUSED,createdAtEpochMillis=System.currentTimeMillis()))
   context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
   println("CHROME_OPTIONS_READY=$name DATE=$date TIME=22:00")
   withTimeout(180_000) { while(repo.get(id)?.speedLimitBytesPerSecond!=300_000L||repo.get(id)?.schedule?.startEpochMillis!=expected)delay(100) }
   assertEquals(DownloadSchedule.Kind.ONCE,repo.get(id)!!.schedule!!.kind)
   assertEquals(DownloadState.PAUSED,repo.get(id)!!.state)
   assertEquals(0L,repo.get(id)!!.downloadedBytes)
   println("CHROME_OPTIONS_APPLIED")
   withTimeout(180_000) { while(repo.get(id)?.speedLimitBytesPerSecond!=null||repo.get(id)?.schedule!=null)delay(100) }
   assertEquals(DownloadState.PAUSED,repo.get(id)!!.state)
   assertEquals(0L,repo.get(id)!!.downloadedBytes)
   assertEquals(originals,repo.downloads.value.filter { it.id!=id })
   assertEquals(browsers,WebPanelRuntime.access.browsers.value)
   assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all)
   delay(3000)
  } finally { repo.delete(id) }
 }
}
