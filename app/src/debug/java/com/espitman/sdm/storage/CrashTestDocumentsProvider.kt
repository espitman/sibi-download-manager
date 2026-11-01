package com.espitman.sdm.storage

import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract as DC
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import java.io.File
import java.io.RandomAccessFile

/** Durable, bounded test volume. Included only in debug; never backed by user files. */
class CrashTestDocumentsProvider : DocumentsProvider() {
 private val ctx get()=checkNotNull(context)
 private val volume get()=File(ctx.filesDir,"crash-test-volume").canonicalFile.apply{mkdirs()}
 private val mode get()=File(ctx.externalCacheDir,"copy-fault-mode")
 private val stage get()=File(ctx.externalCacheDir,"copy-fault-stage")
 private val handler by lazy { Handler(HandlerThread("owned-volume-io").apply{start()}.looper) }
 override fun onCreate()=true
 private fun file(id:String):File {
  require(id=="root"||id.startsWith("root/"));val suffix=id.removePrefix("root").removePrefix("/")
  val f=File(volume,suffix);require(f.canonicalFile==f.absoluteFile&&(f==volume||f.canonicalPath.startsWith(volume.canonicalPath+"/")));return f
 }
 private fun id(f:File)="root"+f.relativeTo(volume).path.takeIf{it.isNotEmpty()}?.let{"/$it"}.orEmpty()
 private fun name(value:String){require(value.isNotEmpty()&&value !in setOf(".","..")&&value.none{it=='/'||it=='\\'||it.isISOControl()})}
 private fun hold(value:String){stage.writeText("$value-blocked");while(mode.exists()&&mode.readText().trim()==value)Thread.sleep(50)}
 private fun rows(projection:Array<out String>?,items:List<File>):Cursor {
  val columns=projection?:arrayOf(DC.Document.COLUMN_DOCUMENT_ID,DC.Document.COLUMN_DISPLAY_NAME,DC.Document.COLUMN_MIME_TYPE,DC.Document.COLUMN_FLAGS,DC.Document.COLUMN_SIZE)
  return MatrixCursor(columns).apply{items.forEach{f->val data=mapOf<String,Any>(DC.Document.COLUMN_DOCUMENT_ID to id(f),DC.Document.COLUMN_DISPLAY_NAME to f.name,DC.Document.COLUMN_MIME_TYPE to if(f.isDirectory)DC.Document.MIME_TYPE_DIR else "application/octet-stream",DC.Document.COLUMN_FLAGS to if(f.isDirectory)(DC.Document.FLAG_DIR_SUPPORTS_CREATE or DC.Document.FLAG_SUPPORTS_DELETE or DC.Document.FLAG_SUPPORTS_RENAME) else (DC.Document.FLAG_SUPPORTS_WRITE or DC.Document.FLAG_SUPPORTS_DELETE or DC.Document.FLAG_SUPPORTS_RENAME),DC.Document.COLUMN_SIZE to if(f.isDirectory)0L else f.length(),DC.Document.COLUMN_LAST_MODIFIED to f.lastModified());addRow(columns.map{data[it]}.toTypedArray())}}
 }
 override fun queryRoots(projection:Array<out String>?):Cursor {
  val columns=projection?:arrayOf(DC.Root.COLUMN_ROOT_ID,DC.Root.COLUMN_DOCUMENT_ID,DC.Root.COLUMN_TITLE,DC.Root.COLUMN_FLAGS,DC.Root.COLUMN_MIME_TYPES,DC.Root.COLUMN_AVAILABLE_BYTES)
  val data=mapOf<String,Any>(DC.Root.COLUMN_ROOT_ID to "owned",DC.Root.COLUMN_DOCUMENT_ID to "root",DC.Root.COLUMN_TITLE to "SDM bounded test volume",DC.Root.COLUMN_FLAGS to DC.Root.FLAG_SUPPORTS_CREATE,DC.Root.COLUMN_MIME_TYPES to "*/*",DC.Root.COLUMN_AVAILABLE_BYTES to if(stage.exists()&&stage.readText()=="quota-exhausted")0L else 64L*1024*1024)
  return MatrixCursor(columns).apply{addRow(columns.map{data[it]}.toTypedArray())}
 }
 override fun queryDocument(documentId:String,projection:Array<out String>?)=rows(projection,listOf(file(documentId).also{require(it.exists())}))
 override fun queryChildDocuments(parentDocumentId:String,projection:Array<out String>?,sortOrder:String?)=rows(projection,file(parentDocumentId).listFiles().orEmpty().sortedBy{it.name})
 override fun createDocument(parentDocumentId:String,mimeType:String,displayName:String):String {
  name(displayName);val parent=file(parentDocumentId);require(parent.isDirectory);val f=File(parent,displayName);check(if(mimeType==DC.Document.MIME_TYPE_DIR)f.mkdir() else f.createNewFile());return id(f)
 }
 override fun renameDocument(documentId:String,displayName:String):String {
  name(displayName);val old=file(documentId);val next=File(old.parentFile,displayName);require(!next.exists());check(old.renameTo(next))
  if(mode.exists()&&mode.readText().trim()=="publish"&&next.isDirectory&&!next.name.startsWith(".sdm-"))hold("publish")
  return id(next)
 }
 override fun deleteDocument(documentId:String){check(file(documentId).deleteRecursively())}
 override fun isChildDocument(parentDocumentId:String,documentId:String)=file(documentId).canonicalPath.startsWith(file(parentDocumentId).canonicalPath+"/")
 override fun openDocument(documentId:String,mode:String,signal:CancellationSignal?):ParcelFileDescriptor {
  val f=file(documentId)
  if(!mode.contains('w')||f.name!="large.bin")return ParcelFileDescriptor.open(f,ParcelFileDescriptor.parseMode(mode))
  val raf=RandomAccessFile(f,"rw");raf.setLength(0)
  return ctx.getSystemService(StorageManager::class.java).openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_WRITE,object:ProxyFileDescriptorCallback(){
   override fun onGetSize()=raf.length()
   override fun onRead(offset:Long,size:Int,data:ByteArray):Int{synchronized(raf){raf.seek(offset);return raf.read(data,0,size).coerceAtLeast(0)}}
   override fun onWrite(offset:Long,size:Int,data:ByteArray):Int {
    if(this@CrashTestDocumentsProvider.mode.exists()&&this@CrashTestDocumentsProvider.mode.readText().trim()=="quota"&&offset+size>65536){stage.writeText("quota-exhausted");throw ErrnoException("owned test volume quota",OsConstants.ENOSPC)}
    synchronized(raf){raf.seek(offset);raf.write(data,0,size);raf.fd.sync()}
    if(offset==0L&&this@CrashTestDocumentsProvider.mode.exists()&&this@CrashTestDocumentsProvider.mode.readText().trim()=="copy")hold("copy")
    return size
   }
   override fun onFsync(){raf.fd.sync()}
   override fun onRelease(){raf.close()}
  },handler)
 }
 companion object {
  const val AUTHORITY="com.espitman.sdm.qa.crash.documents"
  fun tree()=DC.buildTreeDocumentUri(AUTHORITY,"root")
  fun root()=DC.buildDocumentUriUsingTree(tree(),"root")
 }
}
