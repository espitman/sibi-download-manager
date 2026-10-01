package com.espitman.sdm.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadPartFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in visible-flow fixture, never modifies existing downloads or their files. */
@RunWith(AndroidJUnit4::class)
class BrowserSessionUiDeviceTest {
    @Test fun stageVerifyAndCleanBrowserSessionFlow() = runBlocking<Unit> {
        val phase = InstrumentationRegistry.getArguments().getString("phase") ?: return@runBlocking
        val context = ApplicationProvider.getApplicationContext<Context>()
        BrowserRequestContextRegistry.initialize(context)
        val destination = File(context.cacheDir, "browser-session-ui-qa.bin")
        val part = DownloadPartFile.forDestination(destination)
        SqliteDownloadRepository(context).use { repository ->
            repository.awaitInitialized()
            when (phase) {
                "stage" -> {
                    check(repository.get(ID) == null)
                    part.writeBytes(ByteArray(4096) { (it % 256).toByte() })
                    val now = System.currentTimeMillis()
                    repository.insert(Download(id = ID, url = URL, fileName = "Browser session QA.bin", destinationPath = destination.path,
                        state = DownloadState.FAILED, error = "Browser session expired. Sign in again to continue.",
                        downloadedBytes = 4096, totalBytes = 1048576, acceptsRanges = true, etag = "\"qa-same\"",
                        mimeType = "application/octet-stream", sortOrder = Long.MIN_VALUE, createdAtEpochMillis = now))
                    BrowserRequestContextRegistry.put(ID, ScopedRequestContext(URL, "session=expired", referer = "http://127.0.0.1:18773/login"))
                }
                "verify" -> {
                    val download = repository.get(ID)!!
                    assertEquals(DownloadState.COMPLETED, download.state)
                    assertEquals(1048576L, download.downloadedBytes)
                    val file = if (destination.exists()) destination else File(download.destinationPath!!)
                    assertArrayEquals(ByteArray(1048576) { (it % 256).toByte() }, file.readBytes())
                    assertNull(BrowserRequestContextRegistry.get(ID))
                }
                "cleanup" -> { repository.delete(ID); BrowserRequestContextRegistry.remove(ID); part.delete(); destination.delete() }
                else -> error("Unknown phase")
            }
        }
    }
    companion object { const val ID = "browser-session-ui-qa"; const val URL = "http://127.0.0.1:18773/protected/session-qa.bin" }
}
