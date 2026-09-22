package com.espitman.sdm.storage

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedFileIntentSpecTest {
    private val contentUri = "content://com.espitman.sdm.files/external_downloads/guide.pdf"

    @Test
    fun openIntentUsesViewOpenableAndReadGrant() {
        val spec = CompletedFileIntents.open(contentUri, "application/pdf")
        assertEquals(CompletedFileIntents.ACTION_VIEW, spec.action)
        assertEquals("application/pdf", spec.mimeType)
        assertEquals(contentUri, spec.dataUri)
        assertNull(spec.extraStreamUri)
        assertEquals(contentUri, spec.clipDataUri)
        assertEquals(CompletedFileIntents.FLAG_GRANT_READ, spec.flags)
        assertEquals(setOf(CompletedFileIntents.CATEGORY_OPENABLE), spec.categories)
        assertNull(spec.chooserTitle)
        assertTrue(spec.dataUri!!.startsWith("content:"))
    }

    @Test
    fun shareIntentUsesSendStreamClipDataAndChooserTitle() {
        val spec = CompletedFileIntents.share(contentUri, "video/mp4")
        assertEquals(CompletedFileIntents.ACTION_SEND, spec.action)
        assertEquals("video/mp4", spec.mimeType)
        assertNull(spec.dataUri)
        assertEquals(contentUri, spec.extraStreamUri)
        assertEquals(contentUri, spec.clipDataUri)
        assertEquals(CompletedFileIntents.FLAG_GRANT_READ, spec.flags)
        assertTrue(spec.categories.isEmpty())
        assertEquals(CompletedFileIntents.CHOOSER_TITLE, spec.chooserTitle)
        assertEquals("Share file", spec.chooserTitle)
        assertTrue(spec.extraStreamUri!!.startsWith("content:"))
    }

    @Test
    fun fileUrisAreRejectedForOutgoingIntents() {
        val fileUri = "file:///data/data/com.espitman.sdm/files/Downloads/secret.bin"
        val open = runCatching { CompletedFileIntents.open(fileUri, "text/plain") }
        val share = runCatching { CompletedFileIntents.share(fileUri, "text/plain") }
        assertTrue(open.exceptionOrNull() is IllegalArgumentException)
        assertTrue(share.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun resultMappingUsesVisibleCopy() {
        assertNull(CompletedFileActionResult.Launched.message)
        assertEquals("No app can open this file", CompletedFileActionResult.NoAppOpen.message)
        assertEquals("No app can share this file", CompletedFileActionResult.NoAppShare.message)
        assertEquals("File is no longer available", CompletedFileActionResult.FileUnavailable.message)
        assertEquals(
            CompletedFileActionResult.NoAppOpen,
            CompletedFileIntents.fromFailure(CompletedFileAction.Open, ActivityNotFoundException()),
        )
        assertEquals(
            CompletedFileActionResult.NoAppShare,
            CompletedFileIntents.fromFailure(CompletedFileAction.Share, ActivityNotFoundException()),
        )
        assertEquals(
            CompletedFileActionResult.FileUnavailable,
            CompletedFileIntents.fromFailure(CompletedFileAction.Open, SecurityException("revoked")),
        )
        assertEquals(
            CompletedFileActionResult.FileUnavailable,
            CompletedFileIntents.fromFailure(CompletedFileAction.Share, IllegalArgumentException("provider")),
        )
        assertEquals(
            CompletedFileActionResult.FileUnavailable,
            CompletedFileIntents.fromFailure(CompletedFileAction.Open, java.io.FileNotFoundException("gone")),
        )
        assertEquals(
            CompletedFileActionResult.FileUnavailable,
            CompletedFileIntents.fromFailure(CompletedFileAction.Share, IllegalStateException("runtime provider")),
        )
    }
}
