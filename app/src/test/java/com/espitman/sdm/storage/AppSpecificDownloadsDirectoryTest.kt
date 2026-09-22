package com.espitman.sdm.storage

import java.io.File
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AppSpecificDownloadsDirectoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun prefersUsableExternalDownloadsDirectory() {
        val external = temp.newFolder("Download")
        val internal = temp.newFolder("files")
        val resolved = AppSpecificDownloadsDirectory.resolve(external, internal)
        assertEquals(external, resolved)
        assertFalse(File(internal, AppSpecificDownloadsDirectory.INTERNAL_FALLBACK_NAME).exists())
    }

    @Test
    fun createsMissingExternalDirectoryWhenParentExists() {
        val parent = temp.newFolder("external")
        val external = File(parent, "Download")
        val internal = temp.newFolder("files")
        val resolved = AppSpecificDownloadsDirectory.resolve(external, internal)
        assertEquals(external, resolved)
        assertTrue(external.isDirectory)
        assertFalse(File(internal, AppSpecificDownloadsDirectory.INTERNAL_FALLBACK_NAME).exists())
    }

    @Test
    fun fallsBackToInternalDownloadsWhenExternalIsUnavailable() {
        val internal = temp.newFolder("files")
        val fromNull = AppSpecificDownloadsDirectory.resolve(null, internal)
        assertEquals(File(internal, "Downloads"), fromNull)
        assertTrue(fromNull.isDirectory)

        val blockedExternal = temp.newFile("Download")
        val fromFile = AppSpecificDownloadsDirectory.resolve(blockedExternal, internal)
        assertEquals(fromNull, fromFile)
    }

    @Test
    fun throwsWhenNeitherExternalNorInternalDirectoryCanBeCreated() {
        val internal = temp.newFile("files")
        val error = assertThrows(IOException::class.java) {
            AppSpecificDownloadsDirectory.resolve(null, internal)
        }
        assertTrue(error.message!!.startsWith("Failed to create or access downloads directory:"))
        assertTrue(error.message!!.contains(File(internal, "Downloads").absolutePath))
    }
}
