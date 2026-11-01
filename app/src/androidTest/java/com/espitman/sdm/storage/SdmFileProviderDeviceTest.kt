package com.espitman.sdm.storage

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SdmFileProviderDeviceTest {
    @Test fun renamedDownloadRootsRemainReadableAndRejectTraversalWritesAndUnrelatedFiles() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val original=SaveLocationStore.get(context).read()
        val name="sdm-provider-${UUID.randomUUID()}"
        val prefs=context.getSharedPreferences(name,0)
        prefs.edit().putString("app_download_folder_name",name).commit()
        val scoped=object:ContextWrapper(context) {
            override fun getApplicationContext():Context=this
            override fun getSharedPreferences(key:String,mode:Int):SharedPreferences = if(key=="sdm_settings")prefs else super.getSharedPreferences(key,mode)
        }
        val roots=listOf(File(requireNotNull(context.getExternalFilesDir(null)),name),File(context.filesDir,name))
        val provider=SdmFileProvider()
        val info=checkNotNull(context.packageManager.resolveContentProvider(CompletedFileShareAccess.authority(context.packageName),android.content.pm.PackageManager.GET_META_DATA))
        provider.attachInfo(scoped,info)
        val expected=byteArrayOf(1,3,5,7)
        try {
            for(root in roots) {
                val file=File(root,"nested/owned.txt").apply { parentFile!!.mkdirs();writeBytes(expected) }
                val uri=SdmFileProvider.uriForFile(scoped,file)
                assertTrue(uri.pathSegments.first().startsWith("renamed_"))
                requireNotNull(provider.openFile(uri,"r")).use { descriptor ->
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { assertArrayEquals(expected,it.readBytes()) }
                }
                assertEquals("text/plain",provider.getType(uri))
                provider.query(uri,null,null,null,null).use { cursor ->
                    assertTrue(cursor.moveToFirst());assertEquals(file.name,cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                    assertEquals(expected.size.toLong(),cursor.getLong(cursor.getColumnIndexOrThrow(OpenableColumns.SIZE)))
                }
                assertTrue(runCatching { provider.openFile(uri,"rw") }.exceptionOrNull() is IllegalArgumentException)
                assertTrue(runCatching { provider.delete(uri,null,null) }.exceptionOrNull() is UnsupportedOperationException)
                val traversal=Uri.Builder().scheme("content").authority(uri.authority).appendPath(uri.pathSegments[0]).appendPath(name).appendPath("..").appendPath("secret").build()
                assertTrue(runCatching { provider.openFile(traversal,"r") }.exceptionOrNull() is IllegalArgumentException)
                val foreign=uri.buildUpon().path("/${uri.pathSegments[0]}/unrelated/owned.txt").build()
                assertTrue(runCatching { provider.openFile(foreign,"r") }.exceptionOrNull() is IllegalArgumentException)
                prefs.edit().putString("app_download_folder_name",name+"-changed").commit()
                assertTrue(runCatching { provider.openFile(uri,"r") }.exceptionOrNull() is IllegalArgumentException)
                prefs.edit().putString("app_download_folder_name",name).commit()
                assertArrayEquals(expected,file.readBytes())
            }
            val outside=File(context.cacheDir,"$name.bin").apply { writeBytes(expected) }
            try { assertTrue(runCatching { SdmFileProvider.uriForFile(scoped,outside) }.exceptionOrNull() is IllegalArgumentException) }
            finally { outside.delete() }
            assertEquals(original,SaveLocationStore.get(context).read())
        } finally { roots.forEach { it.deleteRecursively() };prefs.edit().clear().commit();provider.shutdown() }
    }
}
