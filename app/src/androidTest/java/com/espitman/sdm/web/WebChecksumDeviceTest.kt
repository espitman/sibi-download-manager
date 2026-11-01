package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebChecksumDeviceTest {
    @Test fun authenticatedVerificationMatchesDetectsMutationAndPreservesRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM checksum fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original = repository.downloads.value.map { it.id }.toSet()
        val directory = File(context.filesDir, "web-checksum-${UUID.randomUUID()}").apply { check(mkdir()) }
        val bytes = ByteArray(32 * 1024 + 3) { (it % 251).toByte() }
        val file = File(directory, "fixture.bin").apply { writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val ids = mutableListOf<String>()
        suspend fun record(reference: String?, state: DownloadState = DownloadState.COMPLETED): Download {
            val now = System.currentTimeMillis()
            val row = Download(id = UUID.randomUUID().toString(), url = "https://example.invalid/checksum-fixture",
                fileName = file.name, destinationPath = file.path, totalBytes = bytes.size.toLong(),
                downloadedBytes = if (state == DownloadState.COMPLETED) bytes.size.toLong() else 0,
                state = state, referenceSha256 = reference, createdAtEpochMillis = now,
                completedAtEpochMillis = if (state == DownloadState.COMPLETED) now else null)
            ids += row.id; repository.insert(row); return row
        }
        fun request(id: String, key: String = UUID.randomUUID().toString(), authorized: Boolean = true, expected: String? = null): LanHttpServer.Response = router.handle(
            LanHttpServer.Request("POST", "/api/download/checksum", buildMap {
                put("content-type", "application/json"); put("idempotency-key", key)
                if (authorized) put("authorization", "Bearer $secret")
            }, JSONObject().put("id", id).apply { if(expected != null)put("expected", expected) }.toString().toByteArray(), "127.0.0.1"))
        fun status(response: LanHttpServer.Response): String { assertEquals(200, response.status); return JSONObject(String(response.body)).getString("status") }
        try {
            val completed = record(hash)
            val copyPath = "/api/download/link/${completed.id}"
            assertEquals(401, router.handle(LanHttpServer.Request("GET", copyPath, emptyMap(), byteArrayOf(), "127.0.0.1")).status)
            val copied = router.handle(LanHttpServer.Request("GET", copyPath, mapOf("authorization" to "Bearer $secret"), byteArrayOf(), "127.0.0.1"))
            assertEquals(200, copied.status)
            assertEquals(completed.url, JSONObject(String(copied.body)).getString("url"))
            assertEquals(401, request(completed.id, authorized = false).status)
            val key = UUID.randomUUID().toString()
            val verified = request(completed.id, key)
            assertEquals("MATCH", status(verified))
            assertEquals(hash, JSONObject(String(verified.body)).getString("actual"))
            assertEquals(hash, JSONObject(String(verified.body)).getString("expected"))
            assertArrayEquals(verified.body, request(completed.id, key).body)
            assertEquals(completed, repository.get(completed.id))
            file.writeBytes(bytes.copyOf().apply { this[100] = (this[100] + 1).toByte() })
            assertEquals("MISMATCH", status(request(completed.id)))
            assertEquals(completed, repository.get(completed.id))
            val noReference = record(null)
            assertEquals("NO_REFERENCE", status(request(noReference.id)))
            file.writeBytes(bytes)
            val manual = request(noReference.id, expected = hash.uppercase())
            assertEquals("MATCH", status(manual))
            assertEquals(hash, JSONObject(String(manual.body)).getString("actual"))
            assertEquals(hash, JSONObject(String(manual.body)).getString("expected"))
            assertEquals("MISMATCH", status(request(noReference.id, expected = "0".repeat(64))))
            assertEquals(400, request(noReference.id, expected = "invalid").status)
            assertEquals(noReference, repository.get(noReference.id))
            val paused = record(hash, DownloadState.PAUSED)
            assertEquals("NOT_COMPLETED", status(request(paused.id)))
            assertTrue(file.delete())
            assertEquals("UNAVAILABLE", status(request(completed.id)))
            assertNotNull(repository.get(completed.id))
            assertEquals(original, repository.downloads.value.map { it.id }.toSet() - ids.toSet())
            access.revoke(browser.id)
            assertEquals(401, request(completed.id).status)
        } finally {
            ids.forEach { repository.delete(it) }
            directory.deleteRecursively()
            access.revoke(browser.id)
            bridge.close()
        }
    }
}
