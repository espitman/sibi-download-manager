package com.espitman.sdm.web

import android.app.PendingIntent
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.download.DailyBulkSchedule
import com.espitman.sdm.download.DailyBulkScheduleReceiver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** Run only in the temporary com.espitman.sdm.qa installation. */
@RunWith(AndroidJUnit4::class)
class WebGlobalSettingsQaDeviceTest {
 @Test fun webSettingsImmediatelyArmBothDailyAlarms()=runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  check(context.packageName.endsWith(".qa")){"Global tests require the isolated QA application"}
  WebPanelRuntime.initialize(context)
  val settings=SettingsRepository.get(context);val original=settings.settings.value
  val access=WebPanelRuntime.access;val pending=access.request("Owned daily fixture","127.0.0.7")
  check(access.approve(pending.first.id));val secret=checkNotNull(access.collect(pending.first.id,pending.second));val browser=access.authorize(secret)
  val bridge=WebAppBridge(context);val router=WebRouter(access,{"Fixture"},{null},bridge::handle)
  fun intent(action:String)=Intent(context,DailyBulkScheduleReceiver::class.java).setAction(action)
  fun alarm(index:Int,action:String)=PendingIntent.getBroadcast(context,400+index,intent(action),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
  try {
   alarm(0,DailyBulkSchedule.ACTION_RESUME)?.cancel();alarm(1,DailyBulkSchedule.ACTION_PAUSE)?.cancel()
   val body=JSONObject().put("dailyBulkScheduleEnabled",true).put("dailyResumeMinute",60).put("dailyPauseMinute",120)
   val response=router.handle(LanHttpServer.Request("POST","/api/settings",mapOf("authorization" to "Bearer $secret","content-type" to "application/json","idempotency-key" to UUID.randomUUID().toString()),body.toString().toByteArray(),"127.0.0.7"))
   assertEquals(200,response.status);assertTrue(settings.settings.value.dailyBulkScheduleEnabled)
   assertNotNull("Web settings must arm the native daily resume alarm immediately",alarm(0,DailyBulkSchedule.ACTION_RESUME))
   assertNotNull("Web settings must arm the native daily pause alarm immediately",alarm(1,DailyBulkSchedule.ACTION_PAUSE))
  } finally {settings.update{original};DailyBulkSchedule.arm(context);access.revoke(browser.id);bridge.close()}
 }
}
