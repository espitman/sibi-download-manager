package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.torrent.TorrentStore
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Only creates and removes its own uniquely named fixture; existing downloads are never changed. */
@RunWith(AndroidJUnit4::class)
class WebPanelCommandsDeviceTest {
    @Test fun uploadSelectionSubmissionLimitScheduleAndRemovalUseThePhoneRepository() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM web fixture test", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalRecords = repository.downloads.value
        val original = originalRecords.map { it.id }.toSet()
        var added: String? = null
        var draft: String? = null
        var destinationDirectory: File? = null
        fun request(path: String, body: JSONObject? = null, bytes: ByteArray? = null, key: String = UUID.randomUUID().toString()): LanHttpServer.Response {
            val headers = mutableMapOf("authorization" to "Bearer $secret")
            if (body != null || bytes != null) {
                headers["idempotency-key"] = key
                headers["content-type"] = if (bytes == null) "application/json" else "application/x-bittorrent"
            }
            return router.handle(LanHttpServer.Request(if (body == null && bytes == null) "GET" else "POST", path, headers,
                bytes ?: body?.toString()?.toByteArray() ?: byteArrayOf(), "127.0.0.1"))
        }
        fun json(response: LanHttpServer.Response): JSONObject = JSONObject(response.body.toString(Charsets.UTF_8))
        try {
            val roots = json(request("/api/folders")).getJSONArray("folders")
            val root = (0 until roots.length()).map { roots.getJSONObject(it) }.first { it.getString("kind") == "app" }
            val destinationName = "SDM-torrent-destination-${UUID.randomUUID()}"
            val folder = json(request("/api/folders/create", JSONObject().put("parent", root.getString("id")).put("name", destinationName))).getString("id")
            destinationDirectory = File(com.espitman.sdm.storage.AppSpecificDownloadsDirectory.from(context), destinationName)
            val metadata = metadata("SDM-web-fixture-${UUID.randomUUID()}")
            val upload = request("/api/torrent/upload", bytes = metadata)
            assertEquals(202, upload.status)
            draft = json(upload).getString("id")
            withTimeout(10_000) {
                while (json(request("/api/torrent/draft/$draft")).getString("status") == "resolving") delay(50)
            }
            val resolved = json(request("/api/torrent/draft/$draft"))
            assertEquals("ready", resolved.getString("status"))
            assertEquals(2, resolved.getJSONArray("files").length())
            assertEquals(original, repository.downloads.value.map { it.id }.toSet())
            val command = JSONObject().put("id", draft).put("selected", JSONArray()).put("seed", false).put("startNow", false).put("folder", folder)
            assertEquals(400, request("/api/torrent/submit", command).status)
            command.put("selected", JSONArray(listOf(1)))
            val key = UUID.randomUUID().toString()
            val submitted = request("/api/torrent/submit", command, key = key)
            assertEquals(200, submitted.status)
            added = json(submitted).getString("id")
            assertEquals(submitted.body.toList(), request("/api/torrent/submit", command, key = key).body.toList())
            val record = checkNotNull(repository.get(added!!))
            assertEquals(DownloadState.PAUSED, record.state)
            AppRepositories.queueScheduler(context).schedule()
            assertEquals(DownloadState.PAUSED, repository.get(record.id)?.state)
            assertEquals(0L, repository.get(record.id)?.downloadedBytes)
            assertEquals(3L, record.totalBytes)
            assertEquals(destinationDirectory.canonicalPath, File(requireNotNull(record.destinationPath)).parentFile!!.canonicalPath)
            assertNull(record.destinationTreeUri)
            assertEquals(setOf(1), TorrentStore(context).selection(record.id).selected)
            val details = json(request("/api/torrent/details/${record.id}"))
            assertEquals(2, details.getJSONArray("files").length())
            assertFalse(details.getJSONArray("files").getJSONObject(0).getBoolean("selected"))
            assertTrue(details.getJSONArray("files").getJSONObject(1).getBoolean("selected"))
            assertTrue(details.getString("magnet").startsWith("magnet:?"))
            assertEquals(200, request("/api/torrent/seed", JSONObject().put("id", record.id).put("seed", true)).status)
            assertTrue(TorrentStore(context).selection(record.id).seed)
            assertEquals(200, request("/api/torrent/seed", JSONObject().put("id", record.id).put("seed", false)).status)
            assertFalse(TorrentStore(context).selection(record.id).seed)
            assertEquals(200, request("/api/download/limit", JSONObject().put("id", record.id).put("unlimited", false).put("value", "300").put("unit", "KB/s")).status)
            assertEquals(300_000L, repository.get(record.id)?.speedLimitBytesPerSecond)
            val schedule = JSONObject().put("kind", "ONCE").put("start", System.currentTimeMillis() + 3_600_000)
            assertEquals(200, request("/api/download/schedule", JSONObject().put("id", record.id).put("schedule", schedule)).status)
            assertEquals(schedule.getLong("start"), repository.get(record.id)?.schedule?.startEpochMillis)
            val saved = repository.get(record.id)!!
            for (value in listOf("0", "-1", "NaN", "Infinity", "invalid")) {
                assertEquals(value, 400, request("/api/download/limit", JSONObject().put("id", record.id).put("unlimited", false).put("value", value).put("unit", "KB/s")).status)
                assertEquals(saved, repository.get(record.id))
            }
            assertEquals(400, request("/api/download/limit", JSONObject().put("id", record.id).put("unlimited", false).put("value", "1").put("unit", "bytes")).status)
            assertEquals(saved, repository.get(record.id))
            val invalidSchedules = listOf(
                JSONObject().put("kind", "DAILY").put("startMinute", 1440).put("endMinute", 60).put("zone", "Asia/Tehran"),
                JSONObject().put("kind", "DAILY").put("startMinute", 30).put("endMinute", 60).put("zone", "Invalid/Zone"),
                JSONObject().put("kind", "ONCE").put("start", schedule.getLong("start")).put("end", schedule.getLong("start")-1),
                JSONObject().put("kind", "ONCE").put("start", 1.5),
            )
            for (invalid in invalidSchedules) {
                assertEquals(invalid.toString(), 400, request("/api/download/schedule", JSONObject().put("id", record.id).put("schedule", invalid)).status)
                assertEquals(saved, repository.get(record.id))
            }
            val fractional = JSONObject().put("id", record.id).put("unlimited", false).put("value", "0.5").put("unit", "KB/s")
            assertEquals(200, request("/api/download/limit", fractional).status)
            assertEquals(500L, repository.get(record.id)?.speedLimitBytesPerSecond)
            assertEquals(200, request("/api/download/limit", JSONObject().put("id", record.id).put("unlimited", true)).status)
            assertNull(repository.get(record.id)?.speedLimitBytesPerSecond)
            assertEquals(200, request("/api/download/schedule", JSONObject().put("id", record.id).put("schedule", JSONObject.NULL)).status)
            assertNull(repository.get(record.id)?.schedule)
            assertEquals(DownloadState.PAUSED, repository.get(record.id)?.state)
            assertEquals(0L, repository.get(record.id)?.downloadedBytes)
            assertEquals(originalRecords, repository.downloads.value.filter { it.id != record.id })

            val remove = request("/api/downloads/remove", JSONObject().put("ids", JSONArray(listOf(record.id))).put("deleteFiles", false).put("confirmed", true))
            assertEquals(200, remove.status)
            assertTrue(json(remove).getJSONArray("results").getJSONObject(0).getBoolean("removed"))
            assertNull(repository.get(record.id))
            added = null
            assertEquals(original, repository.downloads.value.map { it.id }.toSet())
        } finally {
            added?.let { id ->
                val record = repository.get(id)
                if (record?.fileName?.startsWith("SDM-web-fixture-") == true && id !in original) {
                    com.espitman.sdm.download.IncompleteDownloadDeleteCoordinator.delete(id, repository) {
                        com.espitman.sdm.download.DownloadTransferService.cancelTransfer(context, it)
                    }
                }
            }
            destinationDirectory?.let { directory ->
                assertTrue(directory.listFiles().orEmpty().isEmpty())
                assertTrue(directory.delete())
            }
            bridge.close()
            access.revoke(browser.id)
        }
    }
    private fun metadata(name: String): ByteArray {
        val pieces = MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1, 2, 3, 4, 5, 6))
        return encode(mapOf("info" to mapOf("name" to name, "private" to 1, "piece length" to 16384,
            "pieces" to pieces, "files" to listOf(mapOf("length" to 3, "path" to listOf("first.bin")), mapOf("length" to 3, "path" to listOf("second.bin"))))))
    }
    private fun encode(value: Any): ByteArray = ByteArrayOutputStream().apply {
        fun writeValue(v: Any) { when (v) {
            is String -> writeValue(v.toByteArray())
            is ByteArray -> { write("${v.size}:".toByteArray()); write(v) }
            is Number -> write("i${v}e".toByteArray())
            is List<*> -> { write('l'.code); v.forEach { writeValue(it!!) }; write('e'.code) }
            is Map<*, *> -> { write('d'.code); v.entries.sortedBy { it.key.toString() }.forEach { writeValue(it.key!!); writeValue(it.value!!) }; write('e'.code) }
            else -> error("Unsupported fixture")
        } }
        writeValue(value)
    }.toByteArray()
}
