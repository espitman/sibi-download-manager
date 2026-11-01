package com.espitman.sdm.web

import android.content.Intent
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import com.espitman.sdm.storage.*
import com.espitman.sdm.torrent.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebCopyCrashQaDeviceTest {
 private val context get()=InstrumentationRegistry.getInstrumentation().targetContext.also{check(it.packageName.endsWith(".qa"))}
 private val manifest get()=File(context.externalCacheDir,"copy-crash-fixture.json")
 private val mode get()=File(context.externalCacheDir,"copy-fault-mode")
 private val stage get()=File(context.externalCacheDir,"copy-fault-stage")
 private fun bytes()=ByteArray(8*1024*1024){(it%251).toByte()}
 private fun encode(value:Any):ByteArray=ByteArrayOutputStream().apply{fun put(v:Any){when(v){is String->put(v.toByteArray());is ByteArray->{write("${v.size}:".toByteArray());write(v)};is Number->write("i${v}e".toByteArray());is List<*>->{write(108);v.forEach{put(it!!)};write(101)};is Map<*,*>->{write(100);v.entries.sortedBy{it.key.toString()}.forEach{put(it.key!!);put(it.value!!)};write(101)}}};put(value)}.toByteArray()
 @Test fun prepareAndRunMoveUntilExternalProcessKill()=runBlocking {
  val repo=AppRepositories.downloads(context);repo.awaitInitialized();check(repo.downloads.value.isEmpty());check(!manifest.exists())
  val fault=InstrumentationRegistry.getArguments().getString("fault")?:"copy"
  val permissions=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
  val tree=CrashTestDocumentsProvider.tree();context.grantUriPermission(context.packageName,tree,permissions or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);PersistableTreeUriGrants(context.contentResolver).takeReadWrite(tree.toString())
  val root=File(context.filesDir,"crash-source-${UUID.randomUUID()}").apply{check(mkdir())};val id=UUID.randomUUID().toString()
  val metadata=encode(mapOf("info" to mapOf("name" to root.name,"private" to 1,"piece length" to 16384,"pieces" to MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1,2,3)),"files" to listOf(mapOf("length" to 3,"path" to listOf("first.bin"))))))
  val contents=TorrentStore.inspect(metadata);TorrentStore(context).create(id,contents,TorrentSelection(setOf(0)),root)
  val selectedFile=TorrentPaths.resolve(root,contents.files.single().path).apply{parentFile!!.mkdirs();writeBytes(byteArrayOf(1,2,3))};File(root,"large.bin").writeBytes(bytes())
  val record=Download(id=id,url=contents.magnet,fileName=root.name,destinationPath=root.path,totalBytes=3,downloadedBytes=3,state=DownloadState.COMPLETED,createdAtEpochMillis=1,completedAtEpochMillis=1)
  repo.insert(record);manifest.writeText(JSONObject().put("id",id).put("source",root.path).put("name",root.name).put("fault",fault).put("selectedPath",selectedFile.path).toString());stage.delete();mode.writeText(fault)
  try {
   val result=runCatching{CompletedTorrentFolderMoveCoordinator.move(context,id,CompletedFileMoveCoordinator.Target(tree=tree.toString(),document=CrashTestDocumentsProvider.root(),label="Bounded volume"),repo)}
   if(fault!="quota")throw AssertionError("Expected external process kill before move returned",result.exceptionOrNull())
   assertTrue("Destination quota must fail during a real write",result.isFailure);assertEquals("quota-exhausted",stage.readText());assertEquals(record,repo.get(id));assertArrayEquals(bytes(),File(root,"large.bin").readBytes())
   assertEquals(1,CompletedFolderTree(context).nodes(CrashTestDocumentsProvider.root().toString()).size)
  } finally {if(fault=="quota")cleanup()}
 }
 @Test fun recoverAfterRealProcessDeathKeepsOriginalAndCleansOwnedCopy()=runBlocking {
  check(manifest.exists());mode.delete();val info=JSONObject(manifest.readText());val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  try {
   assertTrue(CompletedTorrentFolderMoveCoordinator.recover(context,repo))
   val row=checkNotNull(repo.get(info.getString("id")));assertEquals(info.getString("source"),row.destinationPath);assertEquals(DownloadState.COMPLETED,row.state)
   val source=File(info.getString("source"));assertArrayEquals(bytes(),File(source,"large.bin").readBytes());assertArrayEquals(byteArrayOf(1,2,3),File(info.getString("selectedPath")).readBytes())
   val remains=CompletedFolderTree(context).nodes(CrashTestDocumentsProvider.root().toString())
   assertEquals("Interrupted move must remove its owned staging/publication copy",1,remains.size)
   assertTrue(TorrentStore(context).selection(row.id).published.isEmpty())
  } finally {cleanup()}
 }
 @Test fun changedInterruptedCopyIsRetainedInsteadOfDeleted()=runBlocking {
  check(manifest.exists());mode.delete();val info=JSONObject(manifest.readText());val repo=AppRepositories.downloads(context);repo.awaitInitialized()
  try {
   val tree=CompletedFolderTree(context);val staging=tree.nodes(CrashTestDocumentsProvider.root().toString()).first{it.directory&&it.path.startsWith(".sdm-move-")&&it.path.count{c->c=='/'}==0}
   val note=tree.create(staging.location,"user-note.txt",false);context.contentResolver.openOutputStream(android.net.Uri.parse(note))!!.use{it.write("Keep this added file".toByteArray())}
   assertFalse(CompletedTorrentFolderMoveCoordinator.recover(context,repo));assertEquals("Keep this added file",tree.input(note).bufferedReader().use{it.readText()})
   assertArrayEquals(bytes(),File(info.getString("source"),"large.bin").readBytes());assertEquals(info.getString("source"),repo.get(info.getString("id"))!!.destinationPath)
  } finally {cleanup()}
 }
 @Test fun cleanOnlyOwnedInterruptedFixture()=runBlocking { if(manifest.exists())cleanup() }
 private suspend fun cleanup(){
  mode.delete();val info=JSONObject(manifest.readText());val repo=AppRepositories.downloads(context);repo.awaitInitialized();repo.delete(info.getString("id"));TorrentStore(context).remove(info.getString("id"));File(info.getString("source")).deleteRecursively()
  File(context.filesDir,"crash-test-volume").listFiles().orEmpty().forEach{it.deleteRecursively()}
  File(context.filesDir,"completed-folder-copies").listFiles().orEmpty().forEach{it.delete()};File(context.filesDir,"completed-folder-moves").listFiles().orEmpty().forEach{it.delete()}
  PersistableTreeUriGrants(context.contentResolver).releaseReadWrite(CrashTestDocumentsProvider.tree().toString());manifest.delete();stage.delete();assertTrue(repo.downloads.value.isEmpty())
 }
}
