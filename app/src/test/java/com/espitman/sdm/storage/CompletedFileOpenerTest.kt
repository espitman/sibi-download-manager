package com.espitman.sdm.storage

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompletedFileOpenerTest {
    private val localIdentity = CompletedFileIdentity(
        downloadId = "local",
        destinationPath = "/data/data/com.espitman.sdm/files/Downloads/guide.pdf",
        persistedMimeType = "application/octet-stream",
        fileName = "guide.pdf",
    )
    private val contentIdentity = CompletedFileIdentity(
        downloadId = "saf",
        destinationPath = "content://com.android.externalstorage.documents/document/primary%3Atrack.flac",
        persistedMimeType = "audio/flac",
        fileName = "track.flac",
    )
    private val localUri = "content://com.espitman.sdm.files/internal_downloads/guide.pdf"
    private val contentUri = contentIdentity.destinationPath

    @Test
    fun localFileOpenUsesFileProviderContentUriAndResolvedMime() {
        val captured = mutableListOf<CompletedFileIntentSpec>()
        val result = opener(
            shareable = ShareableCompletedFile(localUri, localIdentity.persistedMimeType, localIdentity.fileName),
            extensionMime = { "application/pdf" },
            start = { captured += it },
        ).perform(CompletedFileAction.Open, localIdentity)
        assertEquals(CompletedFileActionResult.Launched, result)
        assertEquals(1, captured.size)
        assertEquals(CompletedFileIntents.ACTION_VIEW, captured.single().action)
        assertEquals(localUri, captured.single().dataUri)
        assertEquals("application/pdf", captured.single().mimeType)
        assertTrue(captured.single().dataUri!!.startsWith("content:"))
        assertEquals(setOf(CompletedFileIntents.CATEGORY_OPENABLE), captured.single().categories)
        assertEquals(CompletedFileIntents.FLAG_GRANT_READ, captured.single().flags)
    }

    @Test
    fun contentUriSharePreservesSafUriAndClipData() {
        val captured = mutableListOf<CompletedFileIntentSpec>()
        val result = opener(
            shareable = ShareableCompletedFile(
                uriString = contentUri,
                persistedMimeType = contentIdentity.persistedMimeType,
                fileName = contentIdentity.fileName,
                contentResolverType = "audio/flac",
            ),
            start = { captured += it },
        ).perform(CompletedFileAction.Share, contentIdentity)
        assertEquals(CompletedFileActionResult.Launched, result)
        assertEquals(CompletedFileIntents.ACTION_SEND, captured.single().action)
        assertEquals(contentUri, captured.single().extraStreamUri)
        assertEquals(contentUri, captured.single().clipDataUri)
        assertEquals("audio/flac", captured.single().mimeType)
        assertEquals("Share file", captured.single().chooserTitle)
        assertTrue(captured.single().extraStreamUri!!.startsWith("content:"))
    }

    @Test
    fun missingDestinationIsUnavailableWithoutLaunching() {
        var started = false
        val result = opener(shareable = null, start = { started = true })
            .perform(CompletedFileAction.Open, localIdentity)
        assertEquals(CompletedFileActionResult.FileUnavailable, result)
        assertEquals("File is no longer available", result.message)
        assertTrue(!started)
    }

    @Test
    fun fileProviderFailureIsUnavailable() {
        val result = opener(
            resolve = { throw IllegalArgumentException("Failed to find configured root") },
        ).perform(CompletedFileAction.Share, localIdentity)
        assertEquals(CompletedFileActionResult.FileUnavailable, result)
    }

    @Test
    fun fileSchemeResolvedUrisAreRejected() {
        val result = opener(
            shareable = ShareableCompletedFile(
                uriString = "file:///storage/emulated/0/Download/secret.bin",
                persistedMimeType = "text/plain",
                fileName = "secret.bin",
            ),
        ).perform(CompletedFileAction.Open, localIdentity)
        assertEquals(CompletedFileActionResult.FileUnavailable, result)
    }

    @Test
    fun noHandlerMapsToOpenAndShareCopy() {
        var started = false
        assertEquals(
            CompletedFileActionResult.NoAppOpen,
            opener(
                shareable = ShareableCompletedFile(localUri, "application/pdf", "guide.pdf"),
                hasHandler = { false },
                start = { started = true },
            ).perform(CompletedFileAction.Open, localIdentity),
        )
        assertEquals(
            CompletedFileActionResult.NoAppShare,
            opener(
                shareable = ShareableCompletedFile(contentUri, "audio/flac", "track.flac"),
                hasHandler = { false },
                start = { started = true },
            ).perform(CompletedFileAction.Share, contentIdentity),
        )
        assertTrue(!started)
        assertEquals("No app can open this file", CompletedFileActionResult.NoAppOpen.message)
        assertEquals("No app can share this file", CompletedFileActionResult.NoAppShare.message)
    }

    @Test
    fun launchFailuresMapWithoutCrashing() {
        assertEquals(
            CompletedFileActionResult.NoAppOpen,
            opener(
                shareable = ShareableCompletedFile(localUri, "application/pdf", "guide.pdf"),
                start = { throw ActivityNotFoundException() },
            ).perform(CompletedFileAction.Open, localIdentity),
        )
        assertEquals(
            CompletedFileActionResult.NoAppShare,
            opener(
                shareable = ShareableCompletedFile(contentUri, "audio/flac", "track.flac"),
                start = { throw ActivityNotFoundException() },
            ).perform(CompletedFileAction.Share, contentIdentity),
        )
        assertEquals(
            CompletedFileActionResult.FileUnavailable,
            opener(
                shareable = ShareableCompletedFile(contentUri, "audio/flac", "track.flac"),
                start = { throw SecurityException("grant revoked") },
            ).perform(CompletedFileAction.Share, contentIdentity),
        )
    }

    @Test
    fun contentResolverMimeWinsOverPersisted() {
        val captured = mutableListOf<CompletedFileIntentSpec>()
        opener(
            shareable = ShareableCompletedFile(
                uriString = contentUri,
                persistedMimeType = "application/octet-stream",
                fileName = "track.flac",
                contentResolverType = "audio/flac",
            ),
            start = { captured += it },
        ).perform(CompletedFileAction.Open, contentIdentity)
        assertEquals("audio/flac", captured.single().mimeType)
    }

    private fun opener(
        shareable: ShareableCompletedFile? = ShareableCompletedFile(localUri, "application/pdf", "guide.pdf"),
        resolve: (CompletedFileIdentity) -> ShareableCompletedFile? = { shareable },
        extensionMime: (String) -> String? = { null },
        hasHandler: (CompletedFileIntentSpec) -> Boolean = { true },
        start: (CompletedFileIntentSpec) -> Unit = {},
    ) = CompletedFileOpener(
        resolve = resolve,
        extensionMime = extensionMime,
        hasHandler = hasHandler,
        start = start,
    )
}
