package com.espitman.sdm.web

import android.app.NotificationManager
import android.content.Intent
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Chrome requests a category folder; real Android UI grants the newly owned directory. */
@RunWith(AndroidJUnit4::class)
class WebNewFolderGrantDeviceTest {
 @Test fun chromeCategoryRequestOpensFromNotificationAndGrantsNewFolder() = runBlocking {
  val defaultDestination=InstrumentationRegistry.getArguments().getString("destination")=="default"
  val expectedCategory=if(defaultDestination)null else FileCategory.VIDEO
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  WebPanelRuntime.start(context)
  withTimeout(30000){while(!WebPanelRuntime.status.value.running)delay(100)}
  val folders=WebPanelRuntime::class.java.methods.single{it.name.startsWith("getFolderAccess$")&&it.parameterCount==0}.invoke(WebPanelRuntime) as WebFolderAccess
  val save=SaveLocationStore.get(context);val originalSave=save.read();val root=checkNotNull(originalSave.treeUri)
  val categories=CategoryFolderStore.get(context);val originalCategories=categories.settings.value;val unavailable=categories.unavailable.value
  val grants=PersistableTreeUriGrants(context.contentResolver);val originalGrants=grants.persistedReadWriteTrees().toSet()
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originalRecords=repo.downloads.value
  val originalBrowsers=WebPanelRuntime.access.browsers.value
  val resolver=context.contentResolver
  val parent=DC.buildDocumentUriUsingTree(android.net.Uri.parse(root),DC.getTreeDocumentId(android.net.Uri.parse(root)))
  val name="SDM-web-grant-${UUID.randomUUID()}"
  val document=checkNotNull(DC.createDocument(resolver,parent,DC.Document.MIME_TYPE_DIR,name))
  val tree=DC.buildTreeDocumentUri(document.authority!!,DC.getDocumentId(document)).toString()
  val prepared=File(context.externalCacheDir,"web-new-folder-prepared")
  val accepted=File(context.externalCacheDir,"web-new-folder-accepted")
  var request:WebFolderAccess.Request?=null
  var renamedTree:String?=null
  try {
   prepared.writeText(name);accepted.delete()
   withTimeout(120000){while(folders.pending.value==null)delay(100)}
   request=checkNotNull(folders.pending.value);assertEquals(expectedCategory,request!!.category)
   withTimeout(10000){while(context.getSystemService(NotificationManager::class.java).activeNotifications.none{it.id==4080&&it.notification.extras.getString("android.title")=="Choose download folder"})delay(100)}
   val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
   check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked){"Unlock the phone before Android picker verification"}
   if(InstrumentationRegistry.getArguments().getString("manualNotification")!="true") {
    check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
    tapText("Choose download folder")
   }
   clickText("Choose folder",60000)
   println("FOLDER_NATIVE_PICKER_REQUESTED")
   withTimeout(15000){while(automation.rootInActiveWindow?.packageName?.toString() in setOf(null,context.packageName,"com.android.systemui"))delay(100)}
   println("FOLDER_DOCUMENT_PICKER_OPEN")
   delay(500)
   var found=false
   repeat(30){
    if(!found){
     val target=findText(name)
     if(target!=null){clickNode(target);found=true}
     else {findScrollable()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);delay(200)}
    }
   }
   if(!found)runCatching{capture("web-folder-picker-missing.png")}
   check(found){"Owned directory not visible in Android picker"}
   withTimeout(10000){while(findText(name)==null)delay(100)}
   capture("web-folder-picker-owned.png")
   clickText("Use this folder")
   clickText("Allow")
   withTimeout(120000){while(folders.status(request!!.owner,request!!.id).getString("state")=="pending")delay(100)}
   assertEquals("accepted",folders.status(request!!.owner,request!!.id).getString("state"))
   if(defaultDestination)assertEquals(tree,save.read().treeUri)
   else assertEquals(tree,categories.settings.value.rules[FileCategory.VIDEO]?.treeUri)
   assertTrue(grants.hasReadWrite(tree));assertTrue(grants.persistedReadWriteTrees().containsAll(originalGrants))
   if(!defaultDestination)assertEquals(originalSave,save.read())
   else assertEquals(originalCategories,categories.settings.value)
   assertTrue("Original records changed during Android folder flow",originalRecords==repo.downloads.value);assertEquals(originalBrowsers,WebPanelRuntime.access.browsers.value)
   val child=checkNotNull(DC.createDocument(resolver,DC.buildDocumentUriUsingTree(android.net.Uri.parse(tree),DC.getTreeDocumentId(android.net.Uri.parse(tree))),"application/octet-stream","owned-grant-proof.bin"))
   val bytes=ByteArray(64){it.toByte()}
   resolver.openOutputStream(child)!!.use{it.write(bytes)}
   assertArrayEquals(bytes,resolver.openInputStream(child)!!.use{it.readBytes()})
   if(defaultDestination&&InstrumentationRegistry.getArguments().getString("rename")=="true") {
    accepted.writeText("ready-for-web-rename")
    val coordinator=FolderRenameCoordinator(context)
    withTimeout(120000){while(!FolderRenameCoordinator.isPending(context)&&save.read().displayLabel==name)delay(100)}
    val newId=DC.getTreeDocumentId(android.net.Uri.parse(tree)).substringBeforeLast('/')+"/"+name+"-renamed"
    renamedTree=DC.buildTreeDocumentUri(document.authority!!,newId).toString()
    if(FolderRenameCoordinator.isPending(context)) {
     clickText("Settings")
     var row=findText("Rename save folder")
     repeat(12){if(row==null){findScrollable()?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);delay(200);row=findText("Rename save folder")}}
     clickNode(checkNotNull(row){"Native rename setting not visible"})
     clickText("Grant access")
     withTimeout(15000){while(automation.rootInActiveWindow?.packageName?.toString() in setOf(null,context.packageName,"com.android.systemui"))delay(100)}
     clickText("Use this folder");clickText("Allow")
    }
    withTimeout(30000){while(FolderRenameCoordinator.isPending(context))delay(100)}
    assertEquals(renamedTree,save.read().treeUri)
    val mapped=android.net.Uri.parse(FolderRenameCoordinator.remapUri(child.toString(),tree,renamedTree!!))
    assertArrayEquals(bytes,resolver.openInputStream(mapped)!!.use{it.readBytes()})
    assertTrue(DC.deleteDocument(resolver,mapped))
    assertTrue("Original records changed during web folder rename",originalRecords==repo.downloads.value)
    assertEquals(originalCategories,categories.settings.value)
    accepted.writeText("web-rename-complete")
   } else {assertTrue(DC.deleteDocument(resolver,child));accepted.writeText("accepted")}
   delay(15000)
  } finally {
   request?.let{folders.cancel(it.owner,it.id)}
   check(!FolderRenameCoordinator.isPending(context)){"Owned web rename needs native access recovery before cleanup"}
   if(defaultDestination)save.persistUserTree(root,originalSave.displayLabel)
   else {categories.save(FileCategory.VIDEO,originalCategories.rules[FileCategory.VIDEO]);if(FileCategory.VIDEO in unavailable)categories.markUnavailable(FileCategory.VIDEO)}
   grants.releaseReadWrite(tree)
   renamedTree?.let{grants.releaseReadWrite(it)}
   val cleanupDocument=renamedTree?.let{DC.buildDocumentUriUsingTree(android.net.Uri.parse(root),DC.getTreeDocumentId(android.net.Uri.parse(it)))}?:document
   check(DC.deleteDocument(resolver,cleanupDocument))
   prepared.delete();accepted.delete()
  }
  assertEquals(originalCategories,categories.settings.value);assertEquals(originalSave,save.read())
  assertEquals(originalGrants,grants.persistedReadWriteTrees().toSet());assertTrue("Original records changed during Android folder flow",originalRecords==repo.downloads.value)
 }
 @Test fun chromeFolderRequestCancellationPreservesPhoneState() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context);WebPanelRuntime.start(context)
  withTimeout(30000){while(!WebPanelRuntime.status.value.running)delay(100)}
  val folders=WebPanelRuntime::class.java.methods.single{it.name.startsWith("getFolderAccess$")&&it.parameterCount==0}.invoke(WebPanelRuntime) as WebFolderAccess
  val save=SaveLocationStore.get(context);val originalSave=save.read()
  val categories=CategoryFolderStore.get(context);val originalCategories=categories.settings.value
  val grants=PersistableTreeUriGrants(context.contentResolver);val originalGrants=grants.persistedReadWriteTrees().toSet()
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();val originalRecords=repo.downloads.value
  val originalBrowsers=WebPanelRuntime.access.browsers.value
  var request:WebFolderAccess.Request?=null
  try {
   withTimeout(120000){while(folders.pending.value==null)delay(100)}
   request=checkNotNull(folders.pending.value)
   val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
   check(!context.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked)
   check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS))
   tapText("Choose download folder")
   val mode=InstrumentationRegistry.getArguments().getString("cancelMode")?:"decline"
   if(mode=="decline")clickText("Decline",15000)
   else if(mode=="picker") {
    clickText("Choose folder",15000)
    withTimeout(15000){while(automation.rootInActiveWindow?.packageName?.toString() in setOf(null,context.packageName,"com.android.systemui"))delay(100)}
    repeat(5) {
     if(automation.rootInActiveWindow?.packageName?.toString()!=context.packageName) {
      check(automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));delay(300)
     }
    }
   } else {
    withTimeout(15000){while(findText("Choose folder")==null)delay(100)}
    println("FOLDER_CANCEL_NATIVE_SHEET_VISIBLE")
   }
   withTimeout(150000){while(folders.status(request!!.owner,request!!.id).getString("state")=="pending")delay(100)}
   assertEquals("cancelled",folders.status(request!!.owner,request!!.id).getString("state"))
   assertEquals(null,folders.pending.value)
   assertEquals(originalSave,save.read());assertEquals(originalCategories,categories.settings.value)
   assertEquals(originalGrants,grants.persistedReadWriteTrees().toSet())
   assertTrue("Original records changed during folder cancellation",originalRecords==repo.downloads.value)
   assertEquals(originalBrowsers,WebPanelRuntime.access.browsers.value)
   delay(5000)
  } finally {request?.let{folders.cancel(it.owner,it.id)}}
 }
 private fun findNode(predicate:(android.view.accessibility.AccessibilityNodeInfo)->Boolean):android.view.accessibility.AccessibilityNodeInfo? {
  val root=InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?:return null
  val nodes=java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo>();nodes.add(root)
  while(nodes.isNotEmpty()) {val node=nodes.removeFirst();if(predicate(node))return node;for(i in 0 until node.childCount)node.getChild(i)?.let{nodes.add(it)}}
  return null
 }
 private fun findText(text:String)=findNode{it.text?.toString()?.equals(text,ignoreCase=true)==true||it.contentDescription?.toString()?.equals(text,ignoreCase=true)==true}
 private fun findScrollable()=findNode{it.isScrollable}
 private fun clickNode(node:android.view.accessibility.AccessibilityNodeInfo) {
  var target:android.view.accessibility.AccessibilityNodeInfo?=node
  while(target!=null&&!target.isClickable)target=target.parent
  check((target?:node).performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)){"Android accessibility click rejected"}
 }
 private suspend fun clickText(text:String,timeout:Long=15000) {
  val clicked=withTimeoutOrNull(timeout){while(true){val node=findText(text);if(node!=null){clickNode(node);return@withTimeoutOrNull true};delay(100)};false}?:false
  check(clicked){"Android control not found: $text; active package=${InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow?.packageName}"}
 }
 private suspend fun tapText(text:String) {
  withTimeout(15000){
   var node=findText(text);while(node==null){delay(100);node=findText(text)}
   val bounds=android.graphics.Rect();node.getBoundsInScreen(bounds);check(!bounds.isEmpty)
   val time=android.os.SystemClock.uptimeMillis();val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
   for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
    val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,bounds.exactCenterX(),bounds.exactCenterY(),0)
    try{event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;check(automation.injectInputEvent(event,true)){"Notification touch rejected"}}finally{event.recycle()}
   }
  }
 }
 private fun capture(name:String) {
  val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()){"Android UI screenshot unavailable"}
  try {val context=InstrumentationRegistry.getInstrumentation().targetContext;java.io.File(context.externalCacheDir,name).outputStream().use{check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
 }

}
