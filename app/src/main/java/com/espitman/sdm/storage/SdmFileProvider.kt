package com.espitman.sdm.storage

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

/** Exact read grants for renamed app download folders, without a broad XML root. */
class SdmFileProvider : FileProvider() {
    private fun renamedFile(uri:Uri):File? {
        val kind=uri.pathSegments.firstOrNull()
        if(kind !in setOf(EXTERNAL,INTERNAL))return null
        val context=checkNotNull(context)
        val name=AppSpecificDownloadsDirectory.folderName(context)
        require(uri.pathSegments.size>=3&&uri.pathSegments[1]==name) { "Download folder changed" }
        val root=downloadRoot(context,kind!!,name)
        val relative=uri.pathSegments.drop(2)
        require(relative.all { it.isNotEmpty()&&it!="."&&it!=".."&&'/' !in it&&'\\' !in it }) { "Invalid download path" }
        val file=File(root,relative.joinToString(File.separator)).canonicalFile
        require(file.path.startsWith(root.path+File.separator)) { "File is outside downloads" }
        return file
    }
    override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor? {
        val file=renamedFile(uri)?:return super.openFile(uri,mode)
        require(mode=="r") { "Downloads are shared read-only" }
        return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun query(uri:Uri,projection:Array<out String>?,selection:String?,selectionArgs:Array<out String>?,sortOrder:String?):Cursor {
        val file=renamedFile(uri)?:return super.query(uri,projection,selection,selectionArgs,sortOrder)
        val columns=(projection?.toList()?:listOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE))
            .filter { it==OpenableColumns.DISPLAY_NAME||it==OpenableColumns.SIZE }
        return MatrixCursor(columns.toTypedArray(),1).apply {
            addRow(columns.map { if(it==OpenableColumns.DISPLAY_NAME)file.name else file.length() })
        }
    }
    override fun getType(uri:Uri):String? {
        val file=renamedFile(uri)?:return super.getType(uri)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase())?:CompletedFileMime.OCTET_STREAM
    }
    override fun delete(uri:Uri,selection:String?,selectionArgs:Array<out String>?):Int {
        if(renamedFile(uri)!=null)throw UnsupportedOperationException("Downloads are shared read-only")
        return super.delete(uri,selection,selectionArgs)
    }
    companion object {
        private const val EXTERNAL="renamed_external_downloads"
        private const val INTERNAL="renamed_internal_downloads"
        private fun downloadRoot(context:Context,kind:String,name:String):File {
            val base=if(kind==EXTERNAL)requireNotNull(context.getExternalFilesDir(null)) else context.filesDir
            val root=File(base,name).canonicalFile
            require(name.isNotBlank()&&root.name==name&&root.parentFile==base.canonicalFile) { "Invalid download folder" }
            return root
        }
        fun uriForFile(context:Context,file:File):Uri {
            val authority=CompletedFileShareAccess.authority(context.packageName)
            try { return getUriForFile(context,authority,file) }
            catch(error:IllegalArgumentException) {
                val canonical=file.canonicalFile
                val name=AppSpecificDownloadsDirectory.folderName(context)
                for(kind in listOf(EXTERNAL,INTERNAL)) {
                    val root=try { downloadRoot(context,kind,name) } catch(_:IllegalArgumentException) { continue }
                    if(canonical.path.startsWith(root.path+File.separator)) {
                        return Uri.Builder().scheme("content").authority(authority).appendPath(kind).appendPath(name)
                            .apply { canonical.relativeTo(root).path.split(File.separatorChar).forEach(::appendPath) }.build()
                    }
                }
                throw error
            }
        }
    }
}
