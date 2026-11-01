package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Isolated loopback access state; production assets/router, no production approvals granted. */
@RunWith(AndroidJUnit4::class)
class WebChromeAccessErrorsDeviceTest {
 @Test fun externalChromeDeniedExpiredAndWrongCodeRemainUnauthenticated() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  val originals=repo.downloads.value;val browsers=WebPanelRuntime.access.browsers.value
  val settings=context.getSharedPreferences("sdm_settings",0).all
  val clock=AtomicLong(System.currentTimeMillis());val access=WebAccess {clock.get()}
  val approvalDenials=AtomicInteger();val codeDenials=AtomicInteger()
  val router=WebRouter(access,{"SDM access fixture"},{name ->
   val type=when {name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".css")->"text/css; charset=utf-8";name.endsWith(".js")->"text/javascript; charset=utf-8";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"}
   type to context.assets.open(name).use {it.readBytes()}
  },{_,_->error("No authenticated commands are allowed in this fixture")})
  val server=LanHttpServer(InetAddress.getByName("127.0.0.1"),18081,{request ->
   router.handle(request).also { response ->
    if(response.status==401&&request.path=="/api/approval")approvalDenials.incrementAndGet()
    if(response.status==401&&request.path=="/api/pair")codeDenials.incrementAndGet()
   }
  }).guardRequestsWith(router::preflight)
  try {
   server.start()
   withTimeout(180000){while(access.requests.value.isEmpty())delay(100)}
   access.deny(access.requests.value.single().id)
   withTimeout(30000){while(approvalDenials.get()<1)delay(100)}
   withTimeout(180000){while(access.requests.value.isEmpty())delay(100)}
   clock.addAndGet(120000);access.expire()
   withTimeout(30000){while(approvalDenials.get()<2)delay(100)}
   withTimeout(180000){while(codeDenials.get()<1)delay(100)}
   delay(10000)
   assertTrue(access.browsers.value.isEmpty());assertTrue(access.requests.value.isEmpty())
   assertEquals(originals,repo.downloads.value);assertEquals(browsers,WebPanelRuntime.access.browsers.value)
   assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all)
  } finally {server.close()}
 }
}
