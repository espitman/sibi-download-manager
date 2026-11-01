package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import com.espitman.sdm.download.DownloadPartFile
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebLinkRefreshDeviceTest {
    @Test fun reviewPreservesIdentityAndRequiresExplicitRestartForChangedFile() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM link refresh fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original = repository.downloads.value
        val directory = File(context.cacheDir, "web-refresh-${UUID.randomUUID()}").apply { check(mkdir()) }
        val ids = mutableListOf<String>()
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            while (!server.isClosed) try {
                server.accept().use { client ->
                    client.soTimeout = 3000
                    val reader = client.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@use
                    var range = false
                    while (true) { val header = reader.readLine() ?: break; if (header.isEmpty()) break; if (header.startsWith("Range:", true)) range = true }
                    val etag = if (line.contains("/changed")) "changed" else "same"
                    val partial = range && !line.startsWith("HEAD")
                    val headers = "HTTP/1.1 ${if (partial) "206 Partial Content" else "200 OK"}\r\nContent-Type: application/octet-stream\r\nContent-Length: ${if (partial) 1 else 100}\r\nETag: \"$etag\"\r\nAccept-Ranges: bytes\r\n${if (partial) "Content-Range: bytes 0-0/100\r\n" else ""}Connection: close\r\n\r\n"
                    client.getOutputStream().write(headers.toByteArray())
                    if (!line.startsWith("HEAD")) client.getOutputStream().write(ByteArray(if (partial) 1 else 100) { 42 })
                }
            } catch (_: Exception) { if (server.isClosed) break }
        }.apply { isDaemon = true; start() }
        suspend fun fixture(): Pair<Download, File> {
            val id = UUID.randomUUID().toString(); ids += id
            val target = File(directory, "$id.bin")
            val part = DownloadPartFile.forDestination(target).apply { writeBytes(ByteArray(40) { it.toByte() }) }
            val row = Download(id = id, url = "http://127.0.0.1:${server.localPort}/expired", fileName = target.name,
                destinationPath = target.path, state = DownloadState.FAILED, error = "HTTP 410", downloadedBytes = 40,
                totalBytes = 100, etag = "\"same\"", sortOrder = 55, createdAtEpochMillis = System.currentTimeMillis(),
                schedule = DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = System.currentTimeMillis() + 86400000))
            repository.insert(row); return row to part
        }
        fun request(path: String, body: JSONObject, key: String = UUID.randomUUID().toString()): LanHttpServer.Response = router.handle(
            LanHttpServer.Request("POST", "/api/download/link/$path", mapOf("authorization" to "Bearer $secret",
                "content-type" to "application/json", "idempotency-key" to key), body.toString().toByteArray(), "127.0.0.1"))
        fun json(response: LanHttpServer.Response): JSONObject { assertEquals(200, response.status); return JSONObject(String(response.body)) }
        try {
            val (same, samePart) = fixture()
            val before = samePart.readBytes()
            val checkBody = JSONObject().put("id", same.id).put("url", "http://127.0.0.1:${server.localPort}/fresh")
            val key = UUID.randomUUID().toString()
            val response = request("check", checkBody, key)
            assertArrayEquals(response.body, request("check", checkBody, key).body)
            val review = json(response); assertTrue(review.getBoolean("preserve"))
            assertEquals(same, repository.get(same.id)); assertArrayEquals(before, samePart.readBytes())
            json(request("apply", JSONObject().put("review", review.getString("review"))))
            assertEquals(40L, repository.get(same.id)?.downloadedBytes)
            assertArrayEquals(before, samePart.readBytes())
            assertTrue(repository.get(same.id)!!.url.endsWith("/fresh"))
            val (changed, changedPart) = fixture()
            val changedReview = json(request("check", JSONObject().put("id", changed.id).put("url", "http://127.0.0.1:${server.localPort}/changed")))
            assertFalse(changedReview.getBoolean("preserve"))
            val apply = JSONObject().put("review", changedReview.getString("review"))
            assertEquals(400, request("apply", apply).status)
            assertEquals(changed, repository.get(changed.id)); assertEquals(40L, changedPart.length())
            json(request("apply", apply.put("restartConfirmed", true)))
            assertEquals(0L, repository.get(changed.id)?.downloadedBytes); assertFalse(changedPart.exists())
            val (cancelled, cancelledPart) = fixture()
            val cancelledReview = json(request("check", JSONObject().put("id", cancelled.id).put("url", "http://127.0.0.1:${server.localPort}/fresh")))
            val cancel = JSONObject().put("review", cancelledReview.getString("review"))
            json(request("cancel", cancel)); assertEquals(400, request("apply", cancel).status)
            assertEquals(cancelled, repository.get(cancelled.id)); assertEquals(40L, cancelledPart.length())
            val (stale, stalePart) = fixture()
            val staleReview = json(request("check", JSONObject().put("id", stale.id).put("url", "http://127.0.0.1:${server.localPort}/fresh")))
            val updatedElsewhere = repository.refreshLink(stale.id, stale,
                com.espitman.sdm.network.DownloadMetadata(url = "http://127.0.0.1:${server.localPort}/phone-change",
                    contentLength = 100, acceptsRanges = true, etag = "\"same\""), false, System.currentTimeMillis())
            val staleBytes = stalePart.readBytes()
            assertEquals(409, request("apply", JSONObject().put("review", staleReview.getString("review"))).status)
            assertEquals(updatedElsewhere, repository.get(stale.id)); assertArrayEquals(staleBytes, stalePart.readBytes())
            // A replay of a successful apply must not reset or resubmit the same download again.
            val (replay, replayPart) = fixture()
            val replayReview = json(request("check", JSONObject().put("id", replay.id).put("url", "http://127.0.0.1:${server.localPort}/changed")))
            val replayBody = JSONObject().put("review", replayReview.getString("review")).put("restartConfirmed", true)
            val applyKey = UUID.randomUUID().toString()
            val applied = request("apply", replayBody, applyKey)
            assertEquals(200, applied.status)
            assertArrayEquals(applied.body, request("apply", replayBody, applyKey).body)
            assertEquals(0L, repository.get(replay.id)?.downloadedBytes); assertFalse(replayPart.exists())
            // Exercise the production bulk routes without resuming any original record.
            val (bulkA, partA) = fixture()
            val (bulkB, partB) = fixture()
            val (bulkC, partC) = fixture()
            val bulkBefore = partA.readBytes()
            val bulkReview = json(request("bulk/check", JSONObject()
                .put("ids", JSONArray(listOf(bulkA.id, bulkB.id, bulkC.id)))
                .put("links", JSONArray(listOf("http://127.0.0.1:${server.localPort}/fresh-a",
                    "http://127.0.0.1:${server.localPort}/fresh-b", "http://127.0.0.1:${server.localPort}/fresh-c")))))
            val bulkToken = bulkReview.getString("review")
            fun mapping(index: Number, id: String) = JSONObject().put("index", index).put("id", id).put("restartConfirmed", false)
            fun bulkBody(vararg items: JSONObject) = JSONObject().put("review", bulkToken).put("mappings", JSONArray(items.toList()))
            assertEquals(400, request("bulk/apply", bulkBody(mapping(0.5, bulkA.id))).status)
            assertEquals(400, request("bulk/apply", bulkBody(mapping(0, bulkA.id), mapping(1, bulkA.id))).status)
            assertEquals(bulkA, repository.get(bulkA.id)); assertArrayEquals(bulkBefore, partA.readBytes())
            val bulkApplyKey = UUID.randomUUID().toString()
            val firstBody = bulkBody(mapping(0, bulkA.id))
            val firstResult = request("bulk/apply", firstBody, bulkApplyKey)
            assertTrue(json(firstResult).getJSONArray("results").getJSONObject(0).getBoolean("ok"))
            assertArrayEquals(firstResult.body, request("bulk/apply", firstBody, bulkApplyKey).body)
            assertArrayEquals(bulkBefore, partA.readBytes()); assertEquals(40L, repository.get(bulkA.id)?.downloadedBytes)
            assertEquals(400, request("bulk/apply", bulkBody(mapping(1, bulkA.id))).status)
            val changedOnPhone = repository.refreshLink(bulkB.id, bulkB,
                com.espitman.sdm.network.DownloadMetadata(url = "http://127.0.0.1:${server.localPort}/phone-b",
                    contentLength = 100, acceptsRanges = true, etag = "\"same\""), false, System.currentTimeMillis())
            val remainingResult = json(request("bulk/apply", bulkBody(mapping(1, bulkB.id), mapping(2, bulkC.id)))).getJSONArray("results")
            assertFalse(remainingResult.getJSONObject(0).getBoolean("ok"))
            assertTrue(remainingResult.getJSONObject(1).getBoolean("ok"))
            assertEquals(changedOnPhone, repository.get(bulkB.id)); assertArrayEquals(bulkBefore, partB.readBytes())
            assertTrue(repository.get(bulkC.id)!!.url.endsWith("/fresh-c")); assertArrayEquals(bulkBefore, partC.readBytes())
            json(request("bulk/cancel", JSONObject().put("review", bulkToken)))
            assertEquals(409, request("bulk/apply", bulkBody(mapping(1, bulkB.id))).status)
            // Expiry must be checked after waiting for the native folder mutation lock.
            val (expired, expiredPart) = fixture()
            val expiredBytes = expiredPart.readBytes()
            var clock = System.currentTimeMillis()
            val expiringManager = WebLinkRefresh(context) { clock }
            val expiryReview = expiringManager.check(browser.id, expired.id,
                "http://127.0.0.1:${server.localPort}/fresh-expiry")
            val mutationGate = com.espitman.sdm.storage.FolderMutationGate.mutex
            mutationGate.lock()
            val blockedApply = try {
                val pendingApply = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    runCatching { expiringManager.apply(browser.id, expiryReview.getString("review"), false) }
                }
                assertFalse(pendingApply.isCompleted)
                clock += 120_001
                pendingApply
            } finally { mutationGate.unlock() }
            val expiredResult = blockedApply.await()
            assertTrue(expiredResult.isFailure)
            assertTrue(expiredResult.exceptionOrNull()!!.message!!.contains("expired"))
            assertEquals(expired, repository.get(expired.id))
            assertArrayEquals(expiredBytes, expiredPart.readBytes())
            // Another approved browser cannot consume this owner's review.
            val (ownedReviewRow, ownedReviewPart) = fixture()
            val reviewManager = WebLinkRefresh(context)
            val ownedReview = reviewManager.check(browser.id,ownedReviewRow.id,"http://127.0.0.1:${server.localPort}/fresh-owned")
            val (otherPending, otherTicket) = access.request("Owned second link-review fixture","127.0.0.8")
            check(access.approve(otherPending.id))
            val otherBrowser=checkNotNull(access.authorize(checkNotNull(access.collect(otherPending.id,otherTicket))))
            try {
                assertTrue(runCatching {reviewManager.apply(otherBrowser.id,ownedReview.getString("review"),false)}.isFailure)
                assertEquals(ownedReviewRow,repository.get(ownedReviewRow.id));assertEquals(40L,ownedReviewPart.length())
            } finally {access.revoke(otherBrowser.id)}
            // Revocation while apply waits for the native mutation gate prevents the write.
            mutationGate.lock()
            val revokedApply = try {
                val pendingApply=async(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    runCatching {reviewManager.apply(browser.id,ownedReview.getString("review"),false)}
                }
                assertFalse(pendingApply.isCompleted)
                access.revoke(browser.id)
                pendingApply
            } finally {mutationGate.unlock()}
            val denied=revokedApply.await()
            assertTrue(denied.isFailure)
            assertTrue(denied.exceptionOrNull() is AccessDenied)
            assertEquals(ownedReviewRow,repository.get(ownedReviewRow.id));assertEquals(40L,ownedReviewPart.length())
            assertEquals(original, repository.downloads.value.filter {it.id !in ids})

        } finally {
            ids.forEach { repository.delete(it) }; directory.deleteRecursively()
            server.close(); worker.join(2000); access.revoke(browser.id); bridge.close()
        }
    }
}
