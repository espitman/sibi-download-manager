package com.espitman.sdm.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedFileMimeTest {
    private val extensions = mapOf(
        "pdf" to "application/pdf",
        "mp4" to "video/mp4",
        "mkv" to "video/x-matroska",
        "txt" to "text/plain",
        "apk" to "application/vnd.android.package-archive",
    )

    @Test
    fun normalizeStripsParametersAndRejectsBlank() {
        assertEquals("application/pdf", CompletedFileMime.normalize("  APPLICATION/PDF; charset=binary  "))
        assertEquals("text/plain", CompletedFileMime.normalize("text/plain;charset=utf-8"))
        assertNull(CompletedFileMime.normalize(null))
        assertNull(CompletedFileMime.normalize(""))
        assertNull(CompletedFileMime.normalize("   "))
        assertNull(CompletedFileMime.normalize("; charset=utf-8"))
    }

    @Test
    fun wildcardsAndGenericBinaryAreNotSpecific() {
        assertFalse(CompletedFileMime.isSpecific("*/*"))
        assertFalse(CompletedFileMime.isSpecific("video/*"))
        assertFalse(CompletedFileMime.isSpecific("application/*"))
        assertFalse(CompletedFileMime.isSpecific("application/octet-stream"))
        assertFalse(CompletedFileMime.isSpecific("application/force-download"))
        assertFalse(CompletedFileMime.isSpecific(""))
        assertTrue(CompletedFileMime.isSpecific("application/pdf"))
        assertTrue(CompletedFileMime.isSpecific("video/mp4"))
    }

    @Test
    fun contentResolverTypeWinsWhenSpecific() {
        assertEquals(
            "application/pdf",
            CompletedFileMime.resolve(
                contentResolverType = "application/pdf; charset=binary",
                persistedMimeType = "video/mp4",
                fileName = "notes.txt",
                extensionMime = extensions::get,
            ),
        )
    }

    @Test
    fun persistedMimeIsUsedWhenContentTypeIsWildcardOrBlank() {
        assertEquals(
            "video/mp4",
            CompletedFileMime.resolve("video/*", "VIDEO/MP4; codecs=avc", "clip.bin", extensions::get),
        )
        assertEquals(
            "text/plain",
            CompletedFileMime.resolve("  ", "text/plain", "clip.bin", extensions::get),
        )
    }

    @Test
    fun extensionFillsInWhenPersistedMimeIsGeneric() {
        assertEquals(
            "video/x-matroska",
            CompletedFileMime.resolve(
                contentResolverType = "application/octet-stream",
                persistedMimeType = "application/force-download",
                fileName = "Dune.mkv",
                extensionMime = extensions::get,
            ),
        )
        assertEquals(
            "application/vnd.android.package-archive",
            CompletedFileMime.resolve(null, null, "app.apk", extensions::get),
        )
    }

    @Test
    fun unknownTypesFallBackToOctetStream() {
        assertEquals(
            CompletedFileMime.OCTET_STREAM,
            CompletedFileMime.resolve(null, null, "payload.bin", extensions::get),
        )
        assertEquals(
            CompletedFileMime.OCTET_STREAM,
            CompletedFileMime.resolve("*/*", "application/octet-stream", "no-extension", extensions::get),
        )
    }
}
