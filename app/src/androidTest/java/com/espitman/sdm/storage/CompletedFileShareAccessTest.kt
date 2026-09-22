package com.espitman.sdm.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CompletedFileShareAccessTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun fileProviderUrisAreContentSchemeForAppDownloadsAndInternalFallback() {
        val external = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "sdm-042-external.bin")
        val internalDir = File(context.filesDir, AppSpecificDownloadsDirectory.INTERNAL_FALLBACK_NAME).apply { mkdirs() }
        val internal = File(internalDir, "sdm-042-internal.bin")
        val staging = File(
            SaveLocationDestinationAllocator.stagingDirectory(
                AppSpecificDownloadsDirectory.from(context),
            ),
            "sdm-042-staging.bin",
        )
        listOf(external, internal, staging).forEach { file ->
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            val uri = CompletedFileShareAccess.uriForFile(context, file)
            assertEquals("content", uri.scheme)
            assertEquals(CompletedFileShareAccess.authority(context.packageName), uri.authority)
            assertFalse(uri.toString().startsWith("file:"))
            assertTrue(file.delete() || !file.exists())
        }
    }

    @Test
    fun fileProviderRejectsFilesOutsideConfiguredRoots() {
        val outside = File(context.cacheDir, "sdm-042-outside.bin").apply { writeBytes(byteArrayOf(9)) }
        val error = runCatching { CompletedFileShareAccess.uriForFile(context, outside) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(outside.delete())
    }

    @Test
    fun openIntentGrantsReadAndUsesViewOpenableContentUri() {
        val file = File(AppSpecificDownloadsDirectory.from(context), "sdm-042-open.pdf").apply {
            writeBytes(byteArrayOf(4, 5, 6))
        }
        val uri = CompletedFileShareAccess.uriForFile(context, file)
        val spec = CompletedFileIntents.open(uri.toString(), "application/pdf")
        val intent = spec.toAndroidIntent()
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/pdf", intent.type)
        assertEquals(uri, intent.data)
        assertEquals("content", intent.data?.scheme)
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE))
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(file.delete())
    }

    @Test
    fun shareIntentCarriesStreamClipDataAndReadGrantForContentUri() {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3Atrack.flac")
        val spec = CompletedFileIntents.share(uri.toString(), "audio/flac")
        val intent = spec.toAndroidIntent()
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("audio/flac", intent.type)
        assertNull(intent.data)
        val stream = requireNotNull(
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_STREAM) as Uri?,
        )
        assertEquals(uri, stream)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertEquals("Share file", spec.chooserTitle)
    }

    @Test
    fun missingLocalFileAndProviderFailureAreUnavailable() {
        val access = CompletedFileShareAccess(context)
        val missing = access.perform(
            CompletedFileAction.Open,
            CompletedFileIdentity(
                downloadId = "missing",
                destinationPath = File(AppSpecificDownloadsDirectory.from(context), "gone-042.bin").absolutePath,
                persistedMimeType = "application/pdf",
                fileName = "gone-042.bin",
            ),
        )
        assertEquals(CompletedFileActionResult.FileUnavailable, missing)

        val outside = File(context.cacheDir, "sdm-042-provider.bin").apply { writeBytes(byteArrayOf(7)) }
        val provider = access.perform(
            CompletedFileAction.Share,
            CompletedFileIdentity(
                downloadId = "outside",
                destinationPath = outside.absolutePath,
                persistedMimeType = "text/plain",
                fileName = "sdm-042-provider.bin",
            ),
        )
        assertEquals(CompletedFileActionResult.FileUnavailable, provider)
        assertTrue(outside.delete())
    }

    @Test
    fun providerAuthorityIsPackageScopedAndNotExported() {
        val info = context.packageManager.resolveContentProvider(
            CompletedFileShareAccess.authority(context.packageName),
            0,
        )
        assertNotNull(info)
        assertEquals(context.packageName, info!!.packageName)
        assertFalse(info.exported)
        val uri = FileProvider.getUriForFile(
            context,
            CompletedFileShareAccess.authority(context.packageName),
            File(AppSpecificDownloadsDirectory.from(context), "authority.bin").apply { writeBytes(byteArrayOf(1)) },
        )
        assertEquals("content", uri.scheme)
        File(AppSpecificDownloadsDirectory.from(context), "authority.bin").delete()
    }
}
