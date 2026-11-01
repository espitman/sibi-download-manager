package com.espitman.sdm.web

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Renames/deletes only files and records created by this test, preserving every pre-existing ID. */
@RunWith(AndroidJUnit4::class)
class WebFilesDeviceTest {
    @Test fun modificationDateUsesDiskMetadataAndMissingFilesStayUnknown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "web-date-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        try {
            val source = File(directory, "date.txt").apply { writeText("owned fixture") }
            val modified = 1_700_000_000_000L
            check(source.setLastModified(modified))
            val record = Download(id = UUID.randomUUID().toString(), url = "https://example.invalid/date.txt",
                fileName = source.name, destinationPath = source.path, totalBytes = source.length(),
                downloadedBytes = source.length(), state = DownloadState.COMPLETED,
                createdAtEpochMillis = modified + 100_000, completedAtEpochMillis = modified + 200_000)
            val files = WebFiles(context)
            assertEquals(modified, files.list(listOf(record)).getJSONArray("files").getJSONObject(0).getLong("modifiedAt"))
            check(source.delete())
            val missing = files.list(listOf(record)).getJSONArray("files").getJSONObject(0)
            assertTrue(missing.isNull("modifiedAt"))
            assertFalse(missing.getBoolean("available"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun modificationDateUsesGrantedPhoneProviderAndMissingDocumentStaysUnknown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val currentTree = AppRepositories.saveLocation(context).current().treeUri
        org.junit.Assume.assumeNotNull(currentTree)
        val tree = android.net.Uri.parse(currentTree)
        org.junit.Assume.assumeTrue(resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission && it.isReadPermission })
        val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree,
            android.provider.DocumentsContract.getTreeDocumentId(tree))
        val name = "sdm-web-date-fixture-${UUID.randomUUID()}.txt"
        val document = checkNotNull(android.provider.DocumentsContract.createDocument(resolver, parent, "text/plain", name))
        try {
            checkNotNull(resolver.openOutputStream(document, "wt")).use { it.write("owned date fixture".toByteArray()) }
            val modified = resolver.query(document, arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)!!.use {
                check(it.moveToFirst()); it.getLong(0)
            }
            check(modified > 0)
            val record = Download(id = UUID.randomUUID().toString(), url = "https://example.invalid/date.txt",
                fileName = name, destinationPath = document.toString(), totalBytes = 18, downloadedBytes = 18,
                state = DownloadState.COMPLETED, createdAtEpochMillis = modified + 100_000,
                completedAtEpochMillis = modified + 200_000)
            val files = WebFiles(context)
            val present = files.list(listOf(record)).getJSONArray("files").getJSONObject(0)
            assertTrue(present.getBoolean("available"))
            assertEquals(modified, present.getLong("modifiedAt"))
            check(android.provider.DocumentsContract.deleteDocument(resolver, document))
            val missing = files.list(listOf(record)).getJSONArray("files").getJSONObject(0)
            assertFalse(missing.getBoolean("available"))
            assertTrue(missing.isNull("modifiedAt"))
        } finally {
            runCatching { android.provider.DocumentsContract.deleteDocument(resolver, document) }
        }
    }

    @Test fun renameCollisionPreviewRangeAndConfirmedDeleteUseSharedStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        val access = WebPanelRuntime.access
        val (pending, ticket) = access.request("SDM file fixture test", "127.0.0.1")
        check(access.approve(pending.id))
        val secret = checkNotNull(access.collect(pending.id, ticket))
        val browser = access.authorize(secret)
        val bridge = WebAppBridge(context)
        val router = WebRouter(access, { "Fixture" }, { null }, bridge::handle)
        val repository = AppRepositories.downloads(context)
        repository.awaitInitialized()
        val original = repository.downloads.value.map { it.id }.toSet()
        val directory = File(context.filesDir, "web-files-fixture-${UUID.randomUUID()}").apply { check(mkdir()) }
        var movedFolder: File? = null
        val id = UUID.randomUUID().toString()
        val source = File(directory, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        val collision = File(directory, "occupied.png").apply { writeBytes(byteArrayOf(8, 9)) }
        val now = System.currentTimeMillis()
        val record = Download(id = id, url = "https://example.invalid/web-files-fixture.png", fileName = source.name,
            mimeType = "image/png", destinationPath = source.path, totalBytes = 5, downloadedBytes = 5,
            state = DownloadState.COMPLETED, createdAtEpochMillis = now, completedAtEpochMillis = now)
        fun request(path: String, body: JSONObject? = null, range: String? = null, key: String = UUID.randomUUID().toString()): LanHttpServer.Response {
            val headers = mutableMapOf("authorization" to "Bearer $secret")
            if (body != null) { headers["idempotency-key"] = key; headers["content-type"] = "application/json" }
            if (range != null) headers["range"] = range
            return router.handle(LanHttpServer.Request(if (body == null) "GET" else "POST", path, headers,
                body?.toString()?.toByteArray() ?: byteArrayOf(), "127.0.0.1"))
        }
        try {
            repository.insert(record)
            val denied = request("/api/files/delete", JSONObject().put("ids", JSONArray(listOf(id))).put("confirmed", false))
            assertEquals(400, denied.status)
            assertTrue(source.exists())
            assertEquals(409, request("/api/files/rename", JSONObject().put("id", id).put("name", collision.name)).status)
            assertArrayEquals(byteArrayOf(8, 9), collision.readBytes())
            assertEquals(source.path, repository.get(id)?.destinationPath)
            assertEquals(409, request("/api/files/rename", JSONObject().put("id", id).put("name", "../escape.png")).status)
            val command = JSONObject().put("id", id).put("name", "renamed.png")
            val key = UUID.randomUUID().toString()
            val renamed = request("/api/files/rename", command, key = key)
            assertEquals(200, renamed.status)
            assertEquals(renamed.body.toList(), request("/api/files/rename", command, key = key).body.toList())
            val destination = File(directory, "renamed.png")
            assertFalse(source.exists())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), destination.readBytes())
            assertEquals(destination.path, repository.get(id)?.destinationPath)
            val ranged = request("/api/files/$id/main/preview", range = "bytes=1-3")
            assertEquals(206, ranged.status)
            assertEquals("bytes 1-3/5", ranged.headers["Content-Range"])
            assertTrue(ranged.headers["Content-Security-Policy"]!!.contains("frame-ancestors 'self'"))
            assertArrayEquals(byteArrayOf(2, 3, 4), ranged.stream!!.use { it.readBytes() })
            val roots = JSONObject(String(request("/api/folders").body)).getJSONArray("folders")
            val appRoot = (0 until roots.length()).map { roots.getJSONObject(it) }.first { it.getString("kind") == "app" }.getString("id")
            val appFolders = JSONObject(String(request("/api/folders/$appRoot").body)).getJSONArray("folders")
            assertTrue((0 until appFolders.length()).none { appFolders.getJSONObject(it).getString("name") == ".sdm-saf-staging" })
            assertEquals(400, request("/api/folders/create", JSONObject().put("parent", appRoot).put("name", ".sdm-saf-staging")).status)
            assertEquals(400, request("/api/folders/create", JSONObject().put("parent", appRoot).put("name", "../escape")).status)
            val folderName = "SDM-web-move-fixture-${UUID.randomUUID()}"
            val created = request("/api/folders/create", JSONObject().put("parent", appRoot).put("name", folderName))
            assertEquals(200, created.status)
            val folderId = JSONObject(String(created.body)).getString("id")
            movedFolder = File(com.espitman.sdm.storage.AppSpecificDownloadsDirectory.from(context), folderName)
            assertTrue(movedFolder!!.isDirectory)
            assertEquals(400, request("/api/folders/create", JSONObject().put("parent", appRoot).put("name", folderName)).status)
            val targetCollision = File(movedFolder, "renamed.png").apply { writeBytes(byteArrayOf(7, 8)) }
            val moveCommand = JSONObject().put("ids", JSONArray(listOf(id))).put("folder", folderId)
            val refusedMove = JSONObject(String(request("/api/files/move", moveCommand).body)).getJSONArray("results").getJSONObject(0)
            assertFalse(refusedMove.getBoolean("moved"))
            assertArrayEquals(byteArrayOf(7, 8), targetCollision.readBytes())
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), destination.readBytes())
            check(targetCollision.delete())
            val moveKey = UUID.randomUUID().toString()
            val moved = request("/api/files/move", moveCommand, key = moveKey)
            assertTrue(String(moved.body), JSONObject(String(moved.body)).getJSONArray("results").getJSONObject(0).getBoolean("moved"))
            assertEquals(moved.body.toList(), request("/api/files/move", moveCommand, key = moveKey).body.toList())
            val movedFile = File(movedFolder, "renamed.png")
            assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), movedFile.readBytes())
            assertFalse(destination.exists())
            assertEquals(movedFile.path, repository.get(id)?.destinationPath)
            assertTrue(movedFolder!!.listFiles()!!.none { it.name.startsWith(".sdm-move-") })
            val deleted = request("/api/files/delete", JSONObject().put("ids", JSONArray(listOf(id))).put("confirmed", true))
            assertEquals(200, deleted.status)
            assertTrue(JSONObject(String(deleted.body)).getJSONArray("results").getJSONObject(0).getBoolean("deleted"))
            assertFalse(movedFile.exists())
            assertNull(repository.get(id))
            assertArrayEquals(byteArrayOf(8, 9), collision.readBytes())
            assertEquals(original, repository.downloads.value.map { it.id }.toSet())
        } finally {
            if (id !in original) repository.delete(id)
            directory.deleteRecursively()
            movedFolder?.deleteRecursively()
            bridge.close()
            access.revoke(browser.id)
        }
    }
}
