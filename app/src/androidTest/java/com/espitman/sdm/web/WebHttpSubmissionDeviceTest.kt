package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadPartFile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import com.espitman.sdm.storage.SdmTestDocumentsProvider
import com.espitman.sdm.storage.PersistableTreeUriGrants

@RunWith(AndroidJUnit4::class)
class WebHttpSubmissionDeviceTest {
    @Test fun mixedBatchKeepsSuccessfulLinksHeldAndReplaysWithoutDuplicates() = exercise(false)
    @Test fun providerChildReceivesCompletedHttpTransferThroughWebCommands() = exercise(true)
    @Test fun submissionOptionsValidateBeforeBatchAndPersistAcrossReplay() = exercise(false, true)
    private fun exercise(provider: Boolean, options: Boolean = false) = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM HTTP submission fixture", if(options) "127.0.0.4" else if(provider) "127.0.0.3" else "127.0.0.2")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalRecords = repository.downloads.value
        val original = originalRecords.map { it.id }.toSet()
        val prefix = "SDM-http-fixture-${UUID.randomUUID()}"
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    var range = false
                    while (true) {
                        val header = reader.readLine() ?: break
                        if (header.isEmpty()) break
                        if (header.startsWith("Range:", true)) range = true
                    }
                    val partial = range && !line.startsWith("HEAD")
                    val length = if (partial) 1 else 128
                    val path = line.split(' ').getOrNull(1)?.substringAfterLast('/') ?: "file.bin"
                    val response = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\n" +
                        "Content-Type: application/octet-stream\r\nContent-Length: $length\r\n" +
                        "Content-Disposition: attachment; filename=\"$path\"\r\nAccept-Ranges: bytes\r\n" +
                        (if (partial) "Content-Range: bytes 0-0/128\r\n" else "") + "Connection: close\r\n\r\n"
                    client.getOutputStream().write(response.toByteArray())
                    if (!line.startsWith("HEAD")) client.getOutputStream().write(ByteArray(length) { 42 })
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        val base = "http://127.0.0.1:${server.localPort}/$prefix"
        fun request(body: JSONObject, key: String, authenticated: Boolean = true, path: String = "/api/downloads/add") = router.handle(
            LanHttpServer.Request("POST", path, buildMap {
                if (authenticated) put("authorization", "Bearer $secret")
                put("content-type", "application/json"); put("idempotency-key", key)
            }, body.toString().toByteArray(), "127.0.0.1"))
        val providerName = "$prefix-provider-root"
        val providerRoot = if (provider) requireNotNull(DC.createDocument(context.contentResolver,
            SdmTestDocumentsProvider.parentUri(), DC.Document.MIME_TYPE_DIR, providerName)) else null
        val grant = providerRoot?.let { SdmTestDocumentsProvider.treeUriFor(DC.getDocumentId(it)) }
        val grants = PersistableTreeUriGrants(context.contentResolver)
        val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        var destinationDirectory: File? = null
        try {
            if (grant != null) {
                context.grantUriPermission(context.packageName, grant, permissions or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                grants.takeReadWrite(grant.toString())
                assertTrue(grants.hasReadWrite(grant.toString()))
            }
            val rootsResponse = router.handle(LanHttpServer.Request("GET", "/api/folders",
                mapOf("authorization" to "Bearer $secret"), byteArrayOf(), "127.0.0.1"))
            assertEquals(200, rootsResponse.status)
            val roots = JSONObject(String(rootsResponse.body)).getJSONArray("folders")
            val root = (0 until roots.length()).map { roots.getJSONObject(it) }.first { if (provider) it.getString("name") == providerName else it.getString("kind") == "app" }
            val folderResponse = request(JSONObject().put("parent", root.getString("id")).put("name", prefix),
                UUID.randomUUID().toString(), path = "/api/folders/create")
            assertEquals(200, folderResponse.status)
            val folder = JSONObject(String(folderResponse.body)).getString("id")
            val (otherPending, otherTicket) = access.request("SDM folder ownership fixture", "127.0.0.1")
            check(access.approve(otherPending.id))
            val otherSecret = checkNotNull(access.collect(otherPending.id, otherTicket))
            val otherBrowser = access.authorize(otherSecret)
            try {
                val foreign = JSONObject().put("links", JSONArray(listOf("$base-foreign.bin")))
                    .put("startNow", false).put("folder", folder)
                val denied = router.handle(LanHttpServer.Request("POST", "/api/downloads/add",
                    mapOf("authorization" to "Bearer $otherSecret", "content-type" to "application/json",
                        "idempotency-key" to UUID.randomUUID().toString()), foreign.toString().toByteArray(), "127.0.0.1"))
                assertEquals(400, denied.status)
                assertEquals(original, repository.downloads.value.map { it.id }.toSet())
                val browseDenied = router.handle(LanHttpServer.Request("GET", "/api/folders/$folder",
                    mapOf("authorization" to "Bearer $otherSecret"), byteArrayOf(), "127.0.0.1"))
                assertEquals(400, browseDenied.status)
            } finally { access.revoke(otherBrowser.id) }
            if (!provider) {
                destinationDirectory = File(com.espitman.sdm.storage.AppSpecificDownloadsDirectory.from(context), prefix)
                assertTrue(destinationDirectory.isDirectory)
            }
            val invalidFolder = JSONObject().put("links", JSONArray(listOf("$base-rejected.bin")))
                .put("startNow", false).put("folder", UUID.randomUUID().toString())
            assertEquals(400, request(invalidFolder, UUID.randomUUID().toString()).status)
            assertEquals(original, repository.downloads.value.map { it.id }.toSet())
            val body = JSONObject().put("links", JSONArray(listOf("$base-first.bin", "invalid-url", "$base-second.bin")))
                .put("startNow", false).put("folder", folder)
            if (options) {
                body.put("schedule", JSONObject().put("kind", "DAILY").put("startMinute", 60).put("endMinute", 420).put("zone", "Asia/Tehran"))
                body.put("speedLimit", JSONObject().put("value", "96").put("unit", "KB/s"))
                val invalidLimit = JSONObject(body.toString()).put("speedLimit", JSONObject().put("value", "0").put("unit", "KB/s"))
                assertEquals(400, request(invalidLimit, UUID.randomUUID().toString()).status)
                val invalidSchedule = JSONObject(body.toString()).put("schedule", JSONObject().put("kind", "DAILY").put("startMinute", 1440).put("endMinute", 420).put("zone", "Asia/Tehran"))
                assertEquals(400, request(invalidSchedule, UUID.randomUUID().toString()).status)
                assertEquals(original, repository.downloads.value.map { it.id }.toSet())
            }
            assertEquals(401, request(body, UUID.randomUUID().toString(), false).status)
            assertEquals(original, repository.downloads.value.map { it.id }.toSet())
            if (grant != null) {
                grants.releaseReadWrite(grant.toString())
                assertFalse(grants.hasReadWrite(grant.toString()))
                assertEquals(400, request(body, UUID.randomUUID().toString()).status)
                assertEquals(original, repository.downloads.value.map { it.id }.toSet())
                context.grantUriPermission(context.packageName, grant, permissions or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
                grants.takeReadWrite(grant.toString())
                assertTrue(grants.hasReadWrite(grant.toString()))
            }
            val key = UUID.randomUUID().toString()
            val response = request(body, key)
            assertEquals(200, response.status)
            val results = JSONObject(String(response.body)).getJSONArray("results")
            assertEquals(3, results.length())
            assertTrue(results.getJSONObject(0).getBoolean("accepted"))
            assertTrue(results.getJSONObject(1).getString("error").isNotBlank())
            assertTrue(results.getJSONObject(2).getBoolean("accepted"))
            assertArrayEquals(response.body, request(body, key).body)
            val created = repository.downloads.value.filter { it.url.startsWith(base) }
            assertEquals(2, created.size)
            for (row in created) {
                assertEquals(DownloadState.PAUSED, row.state)
                assertEquals(0L, row.downloadedBytes)
                if (options) {
                    assertEquals(com.espitman.sdm.domain.IndividualSpeedLimit.parse("96", false), row.speedLimitBytesPerSecond)
                    assertEquals(com.espitman.sdm.domain.DownloadSchedule.Kind.DAILY, row.schedule!!.kind)
                    assertEquals(60, row.schedule!!.startMinuteOfDay)
                    assertEquals(420, row.schedule!!.endMinuteOfDay)
                    assertEquals("Asia/Tehran", row.schedule!!.zoneId)
                }
                assertEquals(128L, row.totalBytes)
                assertTrue(row.fileName.startsWith(prefix))
                if (provider) {
                    assertEquals(grant.toString(), row.destinationTreeUri)
                    assertNotNull(row.destinationParentDocumentUri)
                    assertNotEquals(DC.getDocumentId(providerRoot!!), DC.getDocumentId(Uri.parse(row.destinationParentDocumentUri)))
                } else {
                    assertEquals(destinationDirectory!!.canonicalPath, File(requireNotNull(row.destinationPath)).parentFile!!.canonicalPath)
                    assertNull(row.destinationTreeUri)
                }
                assertTrue(DownloadPartFile.forDestination(File(requireNotNull(row.destinationPath))).exists())
            }
            val transfer = created.first()
            if (options) {
                if (transfer.schedule!!.isOpen(System.currentTimeMillis()) == false) {
                    val blocked = request(JSONObject().put("ids", JSONArray(listOf(transfer.id))).put("action", "resume"),
                        UUID.randomUUID().toString(), path = "/api/download/action")
                    assertFalse(JSONObject(String(blocked.body)).getJSONArray("results").getJSONObject(0).getBoolean("accepted"))
                    assertEquals(0L, repository.get(transfer.id)!!.downloadedBytes)
                }
                assertEquals(200, request(JSONObject().put("id", transfer.id).put("schedule", JSONObject.NULL),
                    UUID.randomUUID().toString(), path = "/api/download/schedule").status)
            }
            val resume = request(JSONObject().put("ids", JSONArray(listOf(transfer.id))).put("action", "resume"),
                UUID.randomUUID().toString(), path = "/api/download/action")
            assertEquals(200, resume.status)
            assertTrue(JSONObject(String(resume.body)).getJSONArray("results").getJSONObject(0).getBoolean("accepted"))
            withTimeout(20_000) {
                while (repository.get(transfer.id)?.state != DownloadState.COMPLETED) {
                    val current = repository.get(transfer.id)!!
                    assertNotEquals("Transfer failed: ${current.error}", DownloadState.FAILED, current.state)
                    delay(50)
                }
            }
            val completed = repository.get(transfer.id)!!
            assertEquals(128L, completed.downloadedBytes)
            if (provider) {
                assertTrue(DC.isChildDocument(context.contentResolver, Uri.parse(completed.destinationParentDocumentUri), Uri.parse(completed.destinationPath)))
                assertEquals(grant.toString(), completed.destinationTreeUri)
            } else assertEquals(destinationDirectory!!.canonicalPath, File(requireNotNull(completed.destinationPath)).parentFile!!.canonicalPath)
            val filePath = "/api/files/${completed.id}/main/download"
            val file = router.handle(LanHttpServer.Request("GET", filePath,
                mapOf("authorization" to "Bearer $secret"), byteArrayOf(), "127.0.0.1"))
            assertEquals(200, file.status)
            assertArrayEquals(ByteArray(128) { 42 }, requireNotNull(file.stream).use { it.readBytes() })
            val range = router.handle(LanHttpServer.Request("GET", filePath,
                mapOf("authorization" to "Bearer $secret", "range" to "bytes=16-31"), byteArrayOf(), "127.0.0.1"))
            assertEquals(206, range.status)
            assertEquals("bytes 16-31/128", range.headers["Content-Range"])
            assertArrayEquals(ByteArray(16) { 42 }, requireNotNull(range.stream).use { it.readBytes() })
            assertEquals(DownloadState.PAUSED, repository.get(created.last().id)?.state)
            assertEquals(original, repository.downloads.value.map { it.id }.toSet() - created.map { it.id }.toSet())
        } finally {
            server.close(); worker.join(3000)
            for (row in repository.downloads.value.filter { it.url.startsWith(base) }) {
                if (row.state == DownloadState.COMPLETED) {
                    val removed = request(JSONObject().put("ids", JSONArray(listOf(row.id))).put("confirmed", true),
                        UUID.randomUUID().toString(), path = "/api/files/delete")
                    assertEquals(200, removed.status)
                    assertTrue(JSONObject(String(removed.body)).getJSONArray("results").getJSONObject(0).getBoolean("deleted"))
                }
                if (!row.destinationPath.orEmpty().startsWith("content:"))
                    DownloadPartFile.forDestination(File(requireNotNull(row.destinationPath))).delete()
                row.destinationPath?.takeUnless { it.startsWith("content:") }?.let { File(it) }?.takeIf { it.name.startsWith(prefix) && it.length() == 0L }?.delete()
                repository.delete(row.id)
            }
            destinationDirectory?.let { directory ->
                assertTrue("Fixture destination must be empty before cleanup", directory.listFiles().orEmpty().isEmpty())
                assertTrue(directory.delete())
            }
            if (providerRoot != null) DC.deleteDocument(context.contentResolver, providerRoot)
            if (grant != null) {
                grants.releaseReadWrite(grant.toString())
                context.revokeUriPermission(grant, permissions)
            }
            access.revoke(browser.id)
            assertEquals(originalRecords, repository.downloads.value)
        }
    }
}
