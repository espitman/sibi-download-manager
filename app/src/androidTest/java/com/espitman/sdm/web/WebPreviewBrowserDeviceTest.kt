package com.espitman.sdm.web

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** A real Chrome decodes owned valid/corrupt PNGs; host signals completion after observing UI. */
@RunWith(AndroidJUnit4::class)
class WebPreviewBrowserDeviceTest {
 @Test fun externalChromePreviewFixturePreservesPhoneData() = runBlocking {
  val context=InstrumentationRegistry.getInstrumentation().targetContext
  WebPanelRuntime.initialize(context)
  WebPanelRuntime.start(context)
  withTimeout(30000){while(!WebPanelRuntime.status.value.running)delay(100)}
  val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  val original=repo.downloads.value
  val settings=SettingsRepository.get(context).settings.value
  val browsers=WebPanelRuntime.access.browsers.value
  val root=File(context.filesDir,"web-preview-${UUID.randomUUID()}").apply{check(mkdir())}
  val good=File(root,"SDM-preview-valid.png")
  val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
  try{bitmap.eraseColor(Color.rgb(212,175,55));good.outputStream().use{check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}}finally{bitmap.recycle()}
  val bad=File(root,"SDM-preview-corrupt.png").apply{writeText("Owned intentionally corrupt PNG fixture")}
  val audio=File(root,"SDM-preview-audio.wav").apply{File(context.externalCacheDir,"web-preview-audio.wav").copyTo(this)}
  val video=File(root,"SDM-preview-video.mp4").apply{File(context.externalCacheDir,"web-preview-video.mp4").copyTo(this)}
  val pdf=File(root,"SDM-preview-document.pdf")
  val document=android.graphics.pdf.PdfDocument()
  try {
   val page=document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(128,128,1).create())
   page.canvas.drawColor(Color.rgb(212,175,55));document.finishPage(page)
   pdf.outputStream().use{document.writeTo(it)}
  } finally {document.close()}
  val files=listOf(good,bad,audio,video,pdf)
  val mimes=listOf("image/png","image/png","audio/wav","video/mp4","application/pdf")
  val ids=files.map{UUID.randomUUID().toString()}
  val prepared=File(context.externalCacheDir,"web-preview-prepared")
  val done=File(context.externalCacheDir,"web-preview-done")
  try {
   for((index,file) in files.withIndex())repo.insert(Download(id=ids[index],url="https://example.invalid/${ids[index]}",fileName=file.name,mimeType=mimes[index],destinationPath=file.path,totalBytes=file.length(),downloadedBytes=file.length(),state=DownloadState.COMPLETED,createdAtEpochMillis=1,completedAtEpochMillis=2))
   done.delete();prepared.writeText(ids.joinToString("\n"))
   withTimeout(360000){while(!done.exists())delay(100)}
   assertEquals(original,repo.downloads.value.filter{it.id !in ids})
   assertEquals(settings,SettingsRepository.get(context).settings.value)
   assertEquals(browsers,WebPanelRuntime.access.browsers.value)
   assertTrue(good.length()>0);assertEquals("Owned intentionally corrupt PNG fixture",bad.readText())
  } finally {
   ids.forEach{repo.delete(it)};check(root.deleteRecursively());prepared.delete();done.delete()
  }
  assertEquals(original,repo.downloads.value)
 }
}
