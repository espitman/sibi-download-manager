package com.espitman.sdm.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileCategoryTest {
    @Test fun specificMimeWinsAndGenericMimeFallsBackToExtension() {
        assertEquals(FileCategory.AUDIO,FileCategory.classify("movie.mkv","audio/mpeg"))
        assertEquals(FileCategory.VIDEO,FileCategory.classify("file.bin","video/mp4; charset=UTF-8"))
        assertEquals(FileCategory.VIDEO,FileCategory.classify("MOVIE.MKV","application/octet-stream"))
        assertEquals(FileCategory.DOCUMENTS,FileCategory.classify("book.zip","application/epub+zip"))
        assertEquals(FileCategory.OTHER,FileCategory.classify("fake.mp4","application/x-executable"))
        for((name,type) in listOf("a.zip" to FileCategory.ARCHIVES,"a.pdf" to FileCategory.DOCUMENTS,"a.png" to FileCategory.IMAGES,"a.unknown" to FileCategory.OTHER)) assertEquals(type,FileCategory.classify(name,null))
    }
    @Test fun capturesRuleHandlesCollisionsAndFallsBackAfterRevocation() {
        val directory=Files.createTempDirectory("sdm-category").toFile()
        val uri="content://com.android.externalstorage.documents/tree/primary%3AMovies"
        var settings=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to CategoryFolderRule(uri,"Movies")))
        var valid=true;var unavailable=false
        val trees=object:UserTreeAccess {
            override fun inspect(treeUri:String)=UserTreeInspection(if(valid) UserTreeState.Writable else UserTreeState.Missing,"Movies",setOf("movie.mkv"))
            override fun createFile(treeUri:String,mimeType:String,displayName:String):CreatedTreeDocument=error("unused")
            override fun writeFrom(documentUri:String,source:File)=error("unused")
            override fun deleteDocument(documentUri:String)=false
        }
        val grants=object:TreeUriGrantStore {
            override fun hasReadWrite(uriString:String)=valid
            override fun takeReadWrite(uriString:String,takeFlags:Int)=PersistableGrantResult.Success(uriString)
            override fun releaseReadWrite(uriString:String)=PersistableGrantResult.Success(uriString)
        }
        try {
            val allocator=CategoryDestinationAllocator({settings},AppPrivateDestinationAllocator({directory}),{directory},grants,trees,{unavailable=true})
            val first=allocator.allocate("movie.mkv",null)
            assertEquals(uri,first.destinationTreeUri);assertNotEquals("movie.mkv",first.fileName)
            val second=allocator.allocate("movie.mkv",null);assertNotEquals(first.fileName,second.fileName)
            settings=CategoryFolderSettings(false)
            assertNull(allocator.allocate("movie.mkv",null).destinationTreeUri)
            assertEquals(uri,first.destinationTreeUri) // previously captured destination remains stable
            settings=CategoryFolderSettings(true,mapOf(FileCategory.VIDEO to CategoryFolderRule(uri,"Movies")))
            valid=false
            assertNull(allocator.allocate("movie.mkv",null).destinationTreeUri);assertTrue(unavailable)
        } finally {directory.deleteRecursively()}
    }
}
