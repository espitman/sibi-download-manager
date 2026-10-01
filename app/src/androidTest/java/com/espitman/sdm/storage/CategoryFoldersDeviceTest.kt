package com.espitman.sdm.storage

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.*
import com.espitman.sdm.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class CategoryFoldersDeviceTest {
 @Test fun actualDocumentTreeClassificationCollisionRuleCaptureAndOfflineRestore() = runBlocking {
  val context=ApplicationProvider.getApplicationContext<Context>()
  val store=CategoryFolderStore.get(context);val original=store.settings.value
  val qaUri=InstrumentationRegistry.getArguments().getString("qaTreeUri")
  assumeTrue("Requires an explicitly selected isolated QA tree",qaUri!=null)
  val rule=CategoryFolderRule(requireNotNull(qaUri),"SDM-Feature-QA")
  val trees=DocumentsContractTreeAccess(context.contentResolver);val grants=PersistableTreeUriGrants(context.contentResolver)
  assertTrue(grants.hasReadWrite(rule.treeUri));assertEquals(UserTreeState.Writable,trees.inspect(rule.treeUri).state)
  val dir=File(context.cacheDir,"category-qa-${UUID.randomUUID()}").apply {mkdirs()}
  val name="sdm-qa-${UUID.randomUUID()}.mp4";var created:String?=null;var published:String?=null
  val db="category-qa-${UUID.randomUUID()}.db";val repo=SqliteDownloadRepository(context,databaseName=db)
  try {
   var policy=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to rule))
   val allocator=CategoryDestinationAllocator({policy},AppPrivateDestinationAllocator({File(dir,"default")}),{dir},grants,trees)
   val first=allocator.allocate(name,"video/mp4");val second=allocator.allocate(name,"video/mp4")
   assertEquals(rule.treeUri,first.destinationTreeUri);assertNotEquals(first.fileName,second.fileName)
   policy=CategoryFolderSettings(false,emptyMap());assertEquals(rule.treeUri,first.destinationTreeUri)
   assertNull(allocator.allocate("unknown.xyz","application/octet-stream").destinationTreeUri)
   val bytes="category folder device QA".toByteArray();first.partFile.writeBytes(bytes)
   created=trees.createFile(rule.treeUri,"video/mp4",first.fileName).documentUri
   trees.writeFrom(created!!,first.partFile)
   assertArrayEquals(bytes,context.contentResolver.openInputStream(Uri.parse(created))!!.use {it.readBytes()})
   assertTrue(trees.inspect(rule.treeUri).childDisplayNames.contains(first.fileName))
   policy=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to rule))
   assertNotEquals(first.fileName,allocator.allocate(name,"video/mp4").fileName)
   var warned=false
   val denied=object:TreeUriGrantStore {
    override fun hasReadWrite(uriString:String)=false
    override fun takeReadWrite(uriString:String,takeFlags:Int)=PersistableGrantResult.Success(uriString)
    override fun releaseReadWrite(uriString:String)=PersistableGrantResult.Success(uriString)
   }
   val revoked=CategoryDestinationAllocator({policy},AppPrivateDestinationAllocator({File(dir,"fallback")}),{dir},denied,trees,{warned=true})
   assertNull(revoked.allocate("revoked.mp4","video/mp4").destinationTreeUri);assertTrue(warned)
   store.replace(CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to rule)));assertTrue(context.getSharedPreferences("sdm_category_folders",Context.MODE_PRIVATE).getBoolean("enabled",false))
   repo.awaitInitialized()
   val backup=Download(url="http://127.0.0.1:8941/restored-device.mp4",fileName="restored-device.mp4",mimeType="video/mp4",state=DownloadState.COMPLETED,downloadedBytes=99,completedAtEpochMillis=2,createdAtEpochMillis=1)
   val coordinator=LinkArchiveCoordinator(repo,allocator,AppRepositories.metadataRetriever(),AppRepositories.queueScheduler(context))
   assertEquals(1,coordinator.restore(listOf(backup)));assertEquals(0,coordinator.restore(listOf(backup)))
   val row=repo.downloads.value.single();assertEquals(DownloadState.PAUSED,row.state);assertEquals(0L,row.downloadedBytes);assertEquals(rule.treeUri,row.destinationTreeUri)
   repo.resumePaused(row.id,System.currentTimeMillis())
   val engine=DownloadTransferEngine(destinationPublisher=SafDownloadDestinationPublisher(trees,AppRepositories.saveLocation(context),{dir}),
       onChunkRead={policy=CategoryFolderSettings(false,emptyMap())})
   engine.executeTransfer(row.id,row.url,DownloadPartFile.forDestination(File(row.destinationPath!!)),repo)
   val complete=repo.get(row.id)!!;published=complete.destinationPath
   assertEquals(DownloadState.COMPLETED,complete.state);assertEquals(rule.treeUri,complete.destinationTreeUri)
   val expected="SDM isolated feature QA\n".repeat(40).toByteArray()
   assertArrayEquals(expected,context.contentResolver.openInputStream(Uri.parse(published))!!.use {it.readBytes()})
  } finally {
   created?.let {trees.deleteDocument(it)};published?.let {trees.deleteDocument(it)};store.replace(original);repo.close();context.deleteDatabase(db);dir.deleteRecursively()
  }
 }
}
