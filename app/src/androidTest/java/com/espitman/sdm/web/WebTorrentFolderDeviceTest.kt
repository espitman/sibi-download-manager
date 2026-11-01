package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import com.espitman.sdm.storage.CompletedFolderTree
import com.espitman.sdm.storage.CompletedTorrentFolderMoveCoordinator
import com.espitman.sdm.storage.PersistableTreeUriGrants
import com.espitman.sdm.storage.SdmTestDocumentsProvider
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import com.espitman.sdm.torrent.TorrentPaths
import com.espitman.sdm.torrent.TorrentSelection
import com.espitman.sdm.torrent.TorrentStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Mutates only uniquely owned fixture folders, torrent metadata and download IDs. */
@RunWith(AndroidJUnit4::class)
class WebTorrentFolderDeviceTest {
    @Test fun providerMovesRejectRevokedAccessAndWriteFailureThenRoundTripSelectedFiles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM provider-move fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalIds = repository.downloads.value.map { it.id }.toSet()
        val id = UUID.randomUUID().toString()
        val root = AppSpecificDownloadsDirectory.from(context)
        val source = File(root, "SDM-web-provider-source-${UUID.randomUUID()}").apply { check(mkdir()) }
        val returned = File(root, "SDM-web-provider-return-${UUID.randomUUID()}").apply { check(mkdir()) }
        val store = TorrentStore(context)
        val grants = PersistableTreeUriGrants(context.contentResolver)
        val providerName = "SDM-web-provider-${UUID.randomUUID()}"
        SdmTestDocumentsProvider.documentsDir(context).mkdirs()
        val providerRoot = checkNotNull(DC.createDocument(context.contentResolver, SdmTestDocumentsProvider.parentUri(), DC.Document.MIME_TYPE_DIR, providerName))
        val grant = SdmTestDocumentsProvider.treeUriFor(DC.getDocumentId(providerRoot))
        val oldWriteFailure = SdmTestDocumentsProvider.writeFailure
        val oldAccessRevoked = SdmTestDocumentsProvider.accessRevoked
        val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        fun request(path: String, body: JSONObject? = null): LanHttpServer.Response {
            val headers = mutableMapOf("authorization" to "Bearer $secret")
            if (body != null) { headers["idempotency-key"] = UUID.randomUUID().toString(); headers["content-type"] = "application/json" }
            return router.handle(LanHttpServer.Request(if (body == null) "GET" else "POST", path, headers,
                body?.toString()?.toByteArray() ?: byteArrayOf(), "127.0.0.1"))
        }
        fun json(response: LanHttpServer.Response) = JSONObject(String(response.body))
        try {
            context.grantUriPermission(context.packageName, grant, permissions or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            grants.takeReadWrite(grant.toString())
            assertTrue("Fixture needs a recorded read/write tree grant", grants.hasReadWrite(grant.toString()))
            val contents = TorrentStore.inspect(metadata(source.name))
            val chosen = contents.files.first()
            store.create(id, contents, TorrentSelection(setOf(chosen.index)), source)
            TorrentPaths.resolve(source, chosen.path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            val now = System.currentTimeMillis()
            repository.insert(Download(id = id, url = contents.magnet, fileName = source.name, mimeType = "application/x-bittorrent",
                destinationPath = source.path, totalBytes = 3, downloadedBytes = 3, state = DownloadState.COMPLETED,
                createdAtEpochMillis = now, completedAtEpochMillis = now))
            val roots = json(request("/api/folders")).getJSONArray("folders")
            val providerId = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("name") == providerName }.getString("id")
            val command = JSONObject().put("ids", JSONArray(listOf(id))).put("folder", providerId)
            SdmTestDocumentsProvider.writeFailure = true
            assertFalse(json(request("/api/files/move", command)).getJSONArray("results").getJSONObject(0).getBoolean("moved"))
            assertTrue(source.exists())
            assertEquals(source.path, repository.get(id)?.destinationPath)
            assertTrue(store.selection(id).published.isEmpty())
            assertEquals(0, json(request("/api/folders/$providerId")).getJSONArray("folders").length())
            SdmTestDocumentsProvider.writeFailure = false
            SdmTestDocumentsProvider.accessRevoked = true
            assertEquals(400, request("/api/files/move", command).status)
            assertTrue(source.exists())
            SdmTestDocumentsProvider.accessRevoked = false
            val moved = request("/api/files/move", command)
            assertTrue(String(moved.body), json(moved).getJSONArray("results").getJSONObject(0).getBoolean("moved"))
            assertFalse(source.exists())
            val providerRecord = checkNotNull(repository.get(id))
            val published = store.selection(id)
            assertEquals(grant.toString(), providerRecord.destinationTreeUri)
            assertEquals(providerRecord.destinationPath, published.rootDocument)
            assertEquals(setOf(chosen.index), published.published.keys)
            assertArrayEquals(byteArrayOf(1, 2, 3), context.contentResolver.openInputStream(Uri.parse(published.published.getValue(chosen.index)))!!.use { it.readBytes() })
            val appId = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("kind") == "app" }.getString("id")
            val folders = json(request("/api/folders/$appId")).getJSONArray("folders")
            val returnId = (0 until folders.length()).map { folders.getJSONObject(it) }.single { it.getString("name") == returned.name }.getString("id")
            val roundTrip = request("/api/files/move", JSONObject().put("ids", JSONArray(listOf(id))).put("folder", returnId))
            assertTrue(String(roundTrip.body), json(roundTrip).getJSONArray("results").getJSONObject(0).getBoolean("moved"))
            val localRoot = File(returned, source.name)
            assertEquals(localRoot.path, repository.get(id)?.destinationPath)
            assertNull(repository.get(id)?.destinationTreeUri)
            assertTrue(store.selection(id).published.isEmpty())
            assertNull(store.selection(id).rootDocument)
            assertArrayEquals(byteArrayOf(1, 2, 3), TorrentPaths.resolve(localRoot, chosen.path).readBytes())
            assertEquals(id, File(localRoot, ".sdm-torrent-owner").readText())
            val streamed = request("/api/files/$id/${chosen.index}/download")
            assertArrayEquals(byteArrayOf(1, 2, 3), streamed.stream!!.use { it.readBytes() })
        } finally {
            SdmTestDocumentsProvider.writeFailure = false; SdmTestDocumentsProvider.accessRevoked = false
            if (id !in originalIds) repository.delete(id)
            source.deleteRecursively(); returned.deleteRecursively(); store.remove(id)
            runCatching { DC.deleteDocument(context.contentResolver, providerRoot) }
            grants.releaseReadWrite(grant.toString())
            context.revokeUriPermission(grant, permissions)
            SdmTestDocumentsProvider.writeFailure = oldWriteFailure; SdmTestDocumentsProvider.accessRevoked = oldAccessRevoked
            bridge.close(); access.revoke(browser.id)
        }
        assertEquals(originalIds, repository.downloads.value.map { it.id }.toSet())
    }
    @Test fun committedMoveRecoveryRetainsChangedOriginalThenFinishesSafely() = runBlocking {
        recoveryFixture(committed = true, changeOriginal = true)
    }
    @Test fun uncommittedMoveRecoveryKeepsOriginalAndRemovesOnlyVerifiedCopy() = runBlocking {
        recoveryFixture(committed = false, changeOriginal = false)
    }
    @Test fun completedTorrentFolderMovePreservesTreeAndUpdatesFileAccess() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM torrent-folder fixture", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalIds = repository.downloads.value.map { it.id }.toSet()
        val appRoot = AppSpecificDownloadsDirectory.from(context)
        val name = "SDM-web-torrent-folder-${UUID.randomUUID()}"
        val source = File(appRoot, name).apply { check(mkdir()) }
        val target = File(appRoot, "SDM-web-torrent-target-${UUID.randomUUID()}").apply { check(mkdir()) }
        val id = UUID.randomUUID().toString()
        val store = TorrentStore(context)
        fun request(path: String, body: JSONObject? = null, key: String = UUID.randomUUID().toString()): LanHttpServer.Response {
            val headers = mutableMapOf("authorization" to "Bearer $secret")
            if (body != null) { headers["idempotency-key"] = key; headers["content-type"] = "application/json" }
            return router.handle(LanHttpServer.Request(if (body == null) "GET" else "POST", path, headers,
                body?.toString()?.toByteArray() ?: byteArrayOf(), "127.0.0.1"))
        }
        fun json(response: LanHttpServer.Response) = JSONObject(String(response.body))
        fun folderId(parent: String, label: String): String {
            val rows = json(request("/api/folders/$parent")).getJSONArray("folders")
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == label }.getString("id")
        }
        try {
            val contents = TorrentStore.inspect(metadata(name))
            val chosen = contents.files.first()
            store.create(id, contents, TorrentSelection(setOf(chosen.index)), source)
            val selected = TorrentPaths.resolve(source, chosen.path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            File(source, "notes.bin").writeBytes(byteArrayOf(9, 8, 7, 6))
            File(source, "empty-folder").mkdir()
            val now = System.currentTimeMillis()
            repository.insert(Download(id = id, url = contents.magnet, fileName = name, mimeType = "application/x-bittorrent",
                destinationPath = source.path, totalBytes = 3, downloadedBytes = 3, state = DownloadState.COMPLETED,
                createdAtEpochMillis = now, completedAtEpochMillis = now))
            val roots = json(request("/api/folders")).getJSONArray("folders")
            val appId = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("kind") == "app" }.getString("id")
            val sourceId = folderId(appId, name)
            val childId = folderId(sourceId, "empty-folder")
            val rejected = json(request("/api/files/move", JSONObject().put("ids", JSONArray(listOf(id))).put("folder", childId)))
                .getJSONArray("results").getJSONObject(0)
            assertFalse(rejected.getBoolean("moved"))
            assertTrue(source.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3), selected.readBytes())
            val targetId = folderId(appId, target.name)
            val collision = File(target, name).apply { mkdir() }
            val collisionFile = File(collision, "pre-existing.bin").apply { writeBytes(byteArrayOf(4, 5)) }
            val command = JSONObject().put("ids", JSONArray(listOf(id))).put("folder", targetId)
            val refused = json(request("/api/files/move", command)).getJSONArray("results").getJSONObject(0)
            assertFalse(refused.getBoolean("moved"))
            assertArrayEquals(byteArrayOf(4, 5), collisionFile.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3), selected.readBytes())
            check(collision.deleteRecursively())
            val key = UUID.randomUUID().toString()
            val moved = request("/api/files/move", command, key)
            val result = json(moved).getJSONArray("results").getJSONObject(0)
            assertTrue(String(moved.body), result.getBoolean("moved"))
            assertEquals(moved.body.toList(), request("/api/files/move", command, key).body.toList())
            val destination = File(target, name)
            assertFalse(source.exists())
            assertEquals(destination.path, repository.get(id)?.destinationPath)
            assertArrayEquals(byteArrayOf(1, 2, 3), TorrentPaths.resolve(destination, chosen.path).readBytes())
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), File(destination, "notes.bin").readBytes())
            assertTrue(File(destination, "empty-folder").isDirectory)
            assertEquals(id, File(destination, ".sdm-torrent-owner").readText())
            assertEquals(destination.canonicalPath, File(store.directory(id), "working-path").readText())
            assertTrue(store.selection(id).published.isEmpty())
            val streamed = request("/api/files/$id/${chosen.index}/download")
            assertEquals(200, streamed.status)
            assertArrayEquals(byteArrayOf(1, 2, 3), streamed.stream!!.use { it.readBytes() })
            val deleted = json(request("/api/files/delete", JSONObject().put("ids", JSONArray(listOf(id))).put("confirmed", true)))
                .getJSONArray("results").getJSONObject(0)
            assertTrue(deleted.getBoolean("deleted"))
            assertFalse(destination.exists())
            assertNull(repository.get(id))
            assertEquals(originalIds, repository.downloads.value.map { it.id }.toSet())
            assertTrue(File(context.filesDir, "completed-folder-moves").listFiles().orEmpty().none { it.name.startsWith("$id-") })
        } finally {
            if (id !in originalIds) repository.delete(id)
            source.deleteRecursively(); target.deleteRecursively(); store.remove(id)
            bridge.close(); access.revoke(browser.id)
        }
    }
    private suspend fun recoveryFixture(committed: Boolean, changeOriginal: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val originalIds = repository.downloads.value.map { it.id }.toSet()
        val id = UUID.randomUUID().toString()
        val root = AppSpecificDownloadsDirectory.from(context)
        val source = File(root, "SDM-web-recovery-source-${UUID.randomUUID()}").apply { check(mkdir()) }
        val destination = File(root, "SDM-web-recovery-target-${UUID.randomUUID()}")
        val store = TorrentStore(context)
        val journal = File(File(context.filesDir, "completed-folder-moves").apply { mkdirs() }, "$id-${UUID.randomUUID()}.json")
        try {
            val contents = TorrentStore.inspect(metadata(source.name))
            val chosen = contents.files.first()
            val selection = TorrentSelection(setOf(chosen.index))
            store.create(id, contents, selection, source)
            TorrentPaths.resolve(source, chosen.path).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 2, 3)) }
            check(source.copyRecursively(destination))
            val tree = CompletedFolderTree(context)
            val before = tree.snapshot(source.path)
            val after = tree.snapshot(destination.path)
            val now = System.currentTimeMillis()
            repository.insert(Download(id = id, url = contents.magnet, fileName = source.name, mimeType = "application/x-bittorrent",
                destinationPath = source.path, totalBytes = 3, downloadedBytes = 3, state = DownloadState.COMPLETED,
                createdAtEpochMillis = now, completedAtEpochMillis = now))
            val selectionJson = JSONObject().put("selected", JSONArray(listOf(chosen.index))).put("seed", false).put("published", JSONObject())
            fun nodesJson(nodes: List<CompletedFolderTree.Node>) = JSONArray(nodes.map { JSONObject().put("path", it.path)
                .put("location", it.location).put("directory", it.directory).put("size", it.size).put("mime", it.mime).put("digest", it.digest) })
            journal.writeText(JSONObject().put("id", id).put("source", source.path).put("destination", destination.path)
                .put("metadata", MessageDigest.getInstance("SHA-256").digest(store.metadata(id)).joinToString("") { "%02x".format(it) })
                .put("before", nodesJson(before)).put("after", nodesJson(after)).put("oldSelection", selectionJson).put("newSelection", selectionJson).toString())
            if (committed) repository.relocateCompleted(id, source.path, destination.path, null, "Fixture", now)
            val addedFile = File(source, "added-after-move.bin")
            if (changeOriginal) {
                addedFile.writeBytes(byteArrayOf(5, 4, 3, 2, 1))
                assertFalse(CompletedTorrentFolderMoveCoordinator.recover(context, repository, id))
                assertTrue(journal.exists())
                assertArrayEquals(byteArrayOf(5, 4, 3, 2, 1), addedFile.readBytes())
                assertArrayEquals(byteArrayOf(1, 2, 3), TorrentPaths.resolve(destination, chosen.path).readBytes())
                assertEquals(destination.path, repository.get(id)?.destinationPath)
                check(addedFile.delete())
            }
            assertTrue(CompletedTorrentFolderMoveCoordinator.recover(context, repository, id))
            assertFalse(journal.exists())
            val retained = if (committed) destination else source
            assertTrue(retained.isDirectory)
            assertFalse((if (committed) source else destination).exists())
            assertEquals(retained.path, repository.get(id)?.destinationPath)
            assertEquals(retained.canonicalPath, File(store.directory(id), "working-path").readText())
            assertArrayEquals(byteArrayOf(1, 2, 3), TorrentPaths.resolve(retained, chosen.path).readBytes())
            assertEquals(selection, TorrentStore(context).selection(id))
        } finally {
            if (id !in originalIds) repository.delete(id)
            source.deleteRecursively(); destination.deleteRecursively(); store.remove(id); journal.delete()
        }
        assertEquals(originalIds, repository.downloads.value.map { it.id }.toSet())
    }
    private fun metadata(name: String): ByteArray = encode(mapOf("info" to mapOf("name" to name, "private" to 1,
        "piece length" to 16384, "pieces" to MessageDigest.getInstance("SHA-1").digest(byteArrayOf(1, 2, 3, 4, 5, 6, 7)),
        "files" to listOf(mapOf("length" to 3, "path" to listOf("nested", "first.bin")), mapOf("length" to 4, "path" to listOf("second.bin"))))))
    private fun encode(value: Any): ByteArray = ByteArrayOutputStream().apply {
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
