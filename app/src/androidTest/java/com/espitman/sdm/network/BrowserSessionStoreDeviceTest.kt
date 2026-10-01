package com.espitman.sdm.network

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BrowserSessionStoreDeviceTest {
    private fun fixture(block: (Context, File) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "browser-grant-test-${UUID.randomUUID()}").apply { mkdirs() }
        val isolated = object : ContextWrapper(base) { override fun getNoBackupFilesDir() = directory }
        try { block(isolated, directory) } finally { directory.deleteRecursively() }
    }
    @Test fun normalGrantSurvivesStoreReopenWithoutPlaintextCredentials() = fixture { context, directory ->
        val grant = ScopedRequestContext("https://example.com/file", "session=secret-unique", "SDM", "https://example.com/page")
        BrowserSessionStore(context).save("download", grant)
        assertEquals(grant, BrowserSessionStore(context).read("download"))
        val bytes = directory.walkTopDown().filter { it.isFile }.single().readBytes()
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("secret-unique"))
        val download = com.espitman.sdm.domain.Download(url = grant.originUrl, fileName = "file", createdAtEpochMillis = 1)
        val export = com.espitman.sdm.data.DownloadBackupCodec.encode(listOf(download), com.espitman.sdm.data.settings.SdmSettings(), 1)
        assertFalse(export.contains("secret-unique")); assertFalse(export.contains("/page")); assertFalse(export.contains("Cookie"))
        BrowserSessionStore(context).remove("download")
        assertNull(BrowserSessionStore(context).read("download"))
    }
    @Test fun privateAndUnretainedGrantsReopenWithoutCookiesOrPageHistory() = fixture { context, _ ->
        val store = BrowserSessionStore(context)
        for ((id, grant) in listOf(
            "private" to ScopedRequestContext("https://example.com/file", "secret", referer = "https://example.com/private-page", isPrivate = true),
            "temporary" to ScopedRequestContext("https://example.com/file", "secret", referer = "https://example.com/page", retainSession = false))) {
            store.save(id, grant)
            val restored = BrowserSessionStore(context).read(id)!!
            assertTrue(restored.requiresSignIn)
            assertNull(restored.cookie); assertNull(restored.referer); assertNull(restored.userAgent)
            assertEquals(emptyMap<String, String>(), restored.headersFor(restored.originUrl))
        }
    }
    @Test fun corruptGrantFailsClosed() = fixture { context, directory ->
        BrowserSessionStore(context).save("broken", ScopedRequestContext("https://example.com/file", "secret"))
        directory.walkTopDown().filter { it.isFile }.single().writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(BrowserSessionStore(context).read("broken")!!.requiresSignIn)
    }
}
