package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.DownloadBackupCodec
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadPartFile
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebBackupDeviceTest {
    @Test fun torrentRestoreRetainsSelectedFilesAndSeedPolicyWithoutStartingEngine() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM torrent backup fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context); repository.awaitInitialized()
        val original = repository.downloads.value.map { it.id }.toSet()
        val store = com.espitman.sdm.torrent.TorrentStore(context)
        val name = "backup-torrent-${UUID.randomUUID()}"
        val contents = com.espitman.sdm.torrent.TorrentStore.inspect(metadata(name))
        val candidate = Download(url = contents.magnet, fileName = name, totalBytes = 7, createdAtEpochMillis = 1)
        val archive = DownloadBackupCodec.encode(listOf(candidate), null, System.currentTimeMillis(), torrents = mapOf(
            contents.magnet to DownloadBackupCodec.TorrentSnapshot(contents.metadata, setOf(0), true))).toByteArray()
        val owned = mutableSetOf<String>()
        fun request(path: String, body: ByteArray? = null, type: String = "application/json"): LanHttpServer.Response = router.handle(
            LanHttpServer.Request(if (body == null) "GET" else "POST", path, buildMap {
                put("authorization", "Bearer $secret")
                if (body != null) { put("content-type", type); put("idempotency-key", UUID.randomUUID().toString()) }
            }, body ?: byteArrayOf(), "127.0.0.1"))
        fun value(response: LanHttpServer.Response): JSONObject { assertEquals(200, response.status); return JSONObject(String(response.body)) }
        try {
            val review = value(request("/api/backup/review", archive, "application/octet-stream"))
            assertEquals(1, review.getInt("torrents")); assertEquals(0, review.getInt("duplicates"))
            val result = value(request("/api/backup/restore", JSONObject().put("review", review.getString("review"))
                .put("settings", false).put("confirmed", true).toString().toByteArray()))
            assertEquals(1, result.getInt("restored"))
            val row = repository.downloads.value.single { it.url == candidate.url }; owned += row.id
            assertEquals(DownloadState.PAUSED, row.state); assertEquals(0L, row.downloadedBytes); assertEquals(3L, row.totalBytes)
            assertEquals(setOf(0), store.selection(row.id).selected); assertTrue(store.selection(row.id).seed)
            assertArrayEquals(contents.metadata, store.metadata(row.id))
            val directory = File(checkNotNull(row.destinationPath))
            assertTrue(directory.isDirectory); assertEquals(row.id, File(directory, ".sdm-torrent-owner").readText())
            assertNull(com.espitman.sdm.torrent.TorrentRuntime.telemetry.value[row.id])
            val exported = value(request("/api/backup/export", JSONObject().put("downloads", true).put("settings", false).toString().toByteArray()))
            val decoded = DownloadBackupCodec.decode(checkNotNull(request(exported.getString("download")).stream).use { String(it.readBytes()) })
            val snapshot = checkNotNull(decoded.torrents[candidate.url])
            assertEquals(setOf(0), snapshot.selected); assertTrue(snapshot.seed); assertArrayEquals(contents.metadata, snapshot.metadata)
            val again = value(request("/api/backup/review", archive, "application/octet-stream"))
            assertEquals(1, again.getInt("duplicates"))
            assertEquals(0, value(request("/api/backup/restore", JSONObject().put("review", again.getString("review"))
                .put("settings", false).put("confirmed", true).toString().toByteArray())).getInt("restored"))
            assertEquals(original, repository.downloads.value.map { it.id }.toSet() - owned)
        } finally {
            // Include a committed fixture even if a later assertion failed before registering its ID.
            repository.downloads.value.filter { it.url == candidate.url && it.id !in original }.forEach { row ->
                store.cleanupWorkingFiles(row.id); store.remove(row.id); repository.delete(row.id)
            }
            access.revoke(browser.id); bridge.close()
        }
    }
    @Test fun exportReviewRestoreSkipsDuplicatesAndPreservesExistingData() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM backup fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original = repository.downloads.value.map { it.id }.toSet()
        val settings = SettingsRepository.get(context).settings.value
        val prefix = "https://example.invalid/web-backup-${UUID.randomUUID()}"
        val existing = Download(url = "$prefix/existing", fileName = "existing-${UUID.randomUUID()}.bin",
            state = DownloadState.PAUSED, createdAtEpochMillis = System.currentTimeMillis())
        val candidate = Download(url = "$prefix/new", fileName = "restored-${UUID.randomUUID()}.bin",
            priority = 2, sortOrder = 42, speedLimitBytesPerSecond = 300000, totalBytes = 100, createdAtEpochMillis = 1)
        fun request(path: String, body: ByteArray? = null, type: String = "application/json", key: String = UUID.randomUUID().toString(), authenticated: Boolean = true): LanHttpServer.Response = router.handle(
            LanHttpServer.Request(if (body == null) "GET" else "POST", path, buildMap {
                if (authenticated) put("authorization", "Bearer $secret")
                if (body != null) { put("content-type", type); put("idempotency-key", key) }
            }, body ?: byteArrayOf(), "127.0.0.1"))
        fun json(path: String, body: JSONObject, key: String = UUID.randomUUID().toString()): LanHttpServer.Response = request(path, body.toString().toByteArray(), key = key)
        fun value(response: LanHttpServer.Response): JSONObject { assertEquals(200, response.status); return JSONObject(String(response.body)) }
        try {
            repository.insert(existing)
            val exported = value(json("/api/backup/export", JSONObject().put("downloads", false).put("settings", true)))
            val downloadPath = exported.getString("download")
            assertEquals(401, request(downloadPath, authenticated = false).status)
            val output = request(downloadPath)
            assertEquals(200, output.status)
            val bytes = checkNotNull(output.stream).use { it.readBytes() }
            assertEquals(exported.getInt("size"), bytes.size)
            LanHttpServer(java.net.InetAddress.getByName("127.0.0.1"), 0, router::handle).use { server ->
                server.start()
                val connection = java.net.URL("http://127.0.0.1:${server.port}$downloadPath").openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000; connection.readTimeout = 5000
                connection.setRequestProperty("Cookie", "sdm_session=$secret")
                try {
                    assertEquals(200, connection.responseCode)
                    assertTrue(connection.getHeaderField("Content-Disposition").contains("sdm-backup-"))
                    assertEquals(bytes.size.toLong(), connection.contentLengthLong)
                    assertArrayEquals(bytes, connection.inputStream.use { it.readBytes() })
                } finally { connection.disconnect() }
            }
            val decoded = DownloadBackupCodec.decode(String(bytes))
            assertEquals(settings, decoded.settings); assertTrue(decoded.downloads.isEmpty())
            assertFalse(String(bytes).contains("destinationPath")); assertFalse(String(bytes).contains("sdm_session"))
            val archive = DownloadBackupCodec.encode(listOf(existing, candidate, candidate), null, System.currentTimeMillis()).toByteArray()
            val review = value(request("/api/backup/review", archive, "application/octet-stream"))
            assertEquals(3, review.getInt("downloads")); assertEquals(2, review.getInt("duplicates")); assertFalse(review.getBoolean("settings"))
            assertEquals(original + existing.id, repository.downloads.value.map { it.id }.toSet())
            val restore = JSONObject().put("review", review.getString("review")).put("settings", false)
            assertEquals(400, json("/api/backup/restore", restore).status)
            assertEquals(original + existing.id, repository.downloads.value.map { it.id }.toSet())
            val key = UUID.randomUUID().toString()
            val restored = json("/api/backup/restore", restore.put("confirmed", true), key)
            assertEquals(1, value(restored).getInt("restored")); assertEquals(2, value(restored).getInt("skipped"))
            assertArrayEquals(restored.body, json("/api/backup/restore", restore, key).body)
            val row = repository.downloads.value.single { it.url == candidate.url }
            assertEquals(DownloadState.PAUSED, row.state); assertEquals(0L, row.downloadedBytes)
            assertEquals(2, row.priority); assertEquals(300000L, row.speedLimitBytesPerSecond)
            assertEquals(existing, repository.get(existing.id)); assertEquals(settings, SettingsRepository.get(context).settings.value)
            assertEquals(400, request("/api/backup/review", "not JSON".toByteArray(), "application/octet-stream").status)
            val cancelReview = value(request("/api/backup/review", archive, "application/octet-stream"))
            val cancel = JSONObject().put("review", cancelReview.getString("review"))
            value(json("/api/backup/cancel", cancel))
            assertEquals(400, json("/api/backup/restore", cancel.put("confirmed", true)).status)
            assertEquals(original, repository.downloads.value.filterNot { it.url.startsWith(prefix) }.map { it.id }.toSet())
        } finally {
            repository.downloads.value.filter { it.url.startsWith(prefix) }.forEach { row ->
                row.destinationPath?.let { DownloadPartFile.forDestination(File(it)).delete() }
                repository.delete(row.id)
            }
            access.revoke(browser.id); bridge.close()
        }
    }
    private fun metadata(name: String): ByteArray = encode(mapOf("info" to mapOf("name" to name, "private" to 1,
        "piece length" to 16384, "pieces" to java.security.MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1, 2, 3, 4, 5, 6, 7)),
        "files" to listOf(mapOf("length" to 3, "path" to listOf("nested", "first.bin")), mapOf("length" to 4, "path" to listOf("second.bin"))))))
    private fun encode(value: Any): ByteArray = java.io.ByteArrayOutputStream().apply {
        fun writeValue(value: Any) { when (value) {
            is String -> writeValue(value.toByteArray())
            is ByteArray -> { write("${value.size}:".toByteArray()); write(value) }
            is Number -> write("i${value}e".toByteArray())
            is List<*> -> { write('l'.code); value.forEach { writeValue(it!!) }; write('e'.code) }
            is Map<*, *> -> { write('d'.code); value.entries.sortedBy { it.key.toString() }.forEach { writeValue(it.key!!); writeValue(it.value!!) }; write('e'.code) }
            else -> error("Unsupported fixture")
        } }
        writeValue(value)
    }.toByteArray()
}
