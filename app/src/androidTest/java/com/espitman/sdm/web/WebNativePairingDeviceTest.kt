package com.espitman.sdm.web

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Real native approval controls and Chrome code submission, preserving existing sessions. */
@RunWith(AndroidJUnit4::class)
class WebNativePairingDeviceTest {
 @Test fun chromeDeclineAllowAndSingleUseCodePreserveExistingData() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context);WebPanelRuntime.start(context)
  withTimeout(30000){while(!WebPanelRuntime.status.value.running)delay(100)}
  val access=WebPanelRuntime.access
  val originals=access.browsers.value
  val originalRequests=access.requests.value.map{it.id}.toSet()
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val records=repo.downloads.value
  val settings=context.getSharedPreferences("sdm_settings",0).all.toMap()
  val bridge=WebAppBridge(context)
  val router=WebRouter(access,{android.os.Build.MODEL},{name ->
   val type=when {name.endsWith(".html")->"text/html; charset=utf-8";name.endsWith(".css")->"text/css; charset=utf-8";name.endsWith(".js")->"text/javascript; charset=utf-8";name.endsWith(".svg")->"image/svg+xml";else->"font/ttf"}
   type to context.assets.open(name).use{it.readBytes()}
  },{request,browser->bridge.handle(request,browser)})
  val server=LanHttpServer(java.net.InetAddress.getByName("127.0.0.1"),18081,router::handle).guardRequestsWith(router::preflight)
  val stage=File(context.externalCacheDir,"web-native-pairing-stage")
  val codeFile=File(context.externalCacheDir,"web-native-pairing-code")
  context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  try {
   server.start()
   stage.writeText("ready")
   withTimeout(120000){while(access.requests.value.isEmpty())delay(100)}
   click("Decline")
   withTimeout(10000){while(access.requests.value.isNotEmpty())delay(100)}
   assertEquals(originals,access.browsers.value);stage.writeText("declined")
   withTimeout(120000){while(access.requests.value.isEmpty())delay(100)}
   click("Allow")
   withTimeout(10000){while(access.browsers.value.size==originals.size)delay(100)}
   val approved=access.browsers.value.single{browser->originals.none{it.id==browser.id}}
   stage.writeText("approved");delay(20000)
   access.revoke(approved.id)
   val code=access.regenerateCode();codeFile.writeText(code.value)
   stage.writeText("code-ready")
   withTimeout(120000){while(access.browsers.value.size==originals.size)delay(100)}
   assertNull(access.code());stage.writeText("code-accepted");delay(20000)
   assertTrue("Original download records changed during native pairing",records==repo.downloads.value)
   assertEquals(settings,context.getSharedPreferences("sdm_settings",0).all.toMap())
  } finally {
   access.browsers.value.filter{browser->originals.none{it.id==browser.id}}.forEach{access.revoke(it.id)}
   access.requests.value.filter{it.id !in originalRequests}.forEach{access.deny(it.id)}
   stage.delete();codeFile.delete();server.close();bridge.close()
  }
  assertEquals(originals,access.browsers.value)
 }
 private suspend fun click(text:String) {
  val clicked=withTimeoutOrNull(15000){while(true){
   val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
   val queue=java.util.ArrayDeque<AccessibilityNodeInfo>();root?.let{queue.add(it)}
   while(queue.isNotEmpty()) {
    val node=queue.removeFirst()
    if(node.text?.toString()==text||node.contentDescription?.toString()==text) {
     var target:AccessibilityNodeInfo?=node
     while(target!=null&&!target.isClickable)target=target.parent
     check((target?:node).performAction(AccessibilityNodeInfo.ACTION_CLICK));return@withTimeoutOrNull true
    }
    for(index in 0 until node.childCount)node.getChild(index)?.let{queue.add(it)}
   }
   delay(100)
  };false}?:false
  if(!clicked) {
   val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
   val context=InstrumentationRegistry.getInstrumentation().targetContext
   automation.takeScreenshot()?.let{bitmap->try{File(context.externalCacheDir,"web-native-pairing-missing.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}finally{bitmap.recycle()}}
   error("Native approval control missing: $text; active=${automation.rootInActiveWindow?.packageName}")
  }
 }
}
