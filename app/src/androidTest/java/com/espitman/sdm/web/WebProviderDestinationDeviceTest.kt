package com.espitman.sdm.web

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract as DC
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.storage.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import com.espitman.sdm.torrent.*

@RunWith(AndroidJUnit4::class)
class WebProviderDestinationDeviceTest {
    @Test fun selectedProviderChildPublishesExactBytesInsideThatDirectory() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val name = "SDM-provider-destination-${UUID.randomUUID()}"
        val root = requireNotNull(DC.createDocument(resolver, SdmTestDocumentsProvider.parentUri(), DC.Document.MIME_TYPE_DIR, name))
        val grant = SdmTestDocumentsProvider.treeUriFor(DC.getDocumentId(root))
        val grants = PersistableTreeUriGrants(resolver)
        val permissions = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val owner = UUID.randomUUID().toString()
        val folders = WebFolders(context)
        val originalLocation = SaveLocationStore.get(context).read()
        var allocated: AllocatedDownloadDestination? = null
        var torrentAllocated: AllocatedDownloadDestination? = null
        val torrentId = UUID.randomUUID().toString()
        val store = TorrentStore(context)
        try {
            context.grantUriPermission(context.packageName, grant, permissions or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            grants.takeReadWrite(grant.toString())
            assertTrue(grants.hasReadWrite(grant.toString()))
            val roots = folders.list(owner, null).getJSONArray("folders")
            val token = (0 until roots.length()).map { roots.getJSONObject(it) }.single { it.getString("name") == name }.getString("id")
            val child = folders.create(owner, token, "Chosen child").getString("id")
            allocated = folders.allocator(owner, child).allocate("$name.bin")
            assertEquals(grant.toString(), allocated.destinationTreeUri)
            val parent = requireNotNull(allocated.destinationParentDocumentUri)
            assertNotEquals(DC.getDocumentId(root), DC.getDocumentId(Uri.parse(parent)))
            val bytes = ByteArray(8193) { (it % 251).toByte() }
            allocated.partFile.writeBytes(bytes)
            val download = Download(url = "https://example.invalid/provider-fixture", fileName = allocated.fileName,
                destinationPath = allocated.destinationPath, destinationTreeUri = allocated.destinationTreeUri,
                destinationParentDocumentUri = parent, destinationDisplayLabel = allocated.destinationDisplayLabel,
                createdAtEpochMillis = System.currentTimeMillis())
            val publisher = SafDownloadDestinationPublisher(DocumentsContractTreeAccess(resolver),
                AppRepositories.saveLocation(context), { AppSpecificDownloadsDirectory.from(context) })
            val published = publisher.afterLocalFinalize(download, allocated.partFile)
            assertTrue(DC.isChildDocument(resolver, Uri.parse(parent), Uri.parse(published.destinationPath)))
            assertArrayEquals(bytes, requireNotNull(resolver.openInputStream(Uri.parse(published.destinationPath))).use { it.readBytes() })
            val inspection = DocumentsContractTreeAccess(resolver).inspectDirectory(grant.toString(), parent)
            assertEquals(setOf(allocated.fileName), inspection.childDisplayNames)
            assertEquals(setOf("Chosen child"), DocumentsContractTreeAccess(resolver).inspect(grant.toString()).childDisplayNames)
            assertFalse(allocated.partFile.exists())
            val contents = TorrentStore.inspect(metadata("$name-torrent"))
            torrentAllocated = folders.allocator(owner, child).allocate(contents.name)
            check(torrentAllocated.partFile.delete())
            val working = File(torrentAllocated.destinationPath).apply { check(mkdir()) }
            val selected = contents.files.first()
            store.create(torrentId, contents, TorrentSelection(setOf(selected.index)), working)
            val selectedBytes = byteArrayOf(1, 2, 3)
            TorrentPaths.resolve(working, selected.path).apply { parentFile!!.mkdirs(); writeBytes(selectedBytes) }
            val torrent = Download(id = torrentId, url = contents.magnet, fileName = torrentAllocated.fileName,
                destinationPath = working.path, destinationTreeUri = torrentAllocated.destinationTreeUri,
                destinationParentDocumentUri = torrentAllocated.destinationParentDocumentUri,
                createdAtEpochMillis = System.currentTimeMillis())
            val torrentRoot = TorrentPublisher.publish(context, torrent, contents, store)
            assertTrue(DC.isChildDocument(resolver, Uri.parse(parent), Uri.parse(torrentRoot)))
            val selection = store.selection(torrentId)
            assertEquals(setOf(selected.index), selection.published.keys)
            assertArrayEquals(selectedBytes, requireNotNull(resolver.openInputStream(Uri.parse(selection.published.getValue(selected.index)))).use { it.readBytes() })
            assertEquals(torrentRoot, TorrentPublisher.publish(context, torrent, contents, store))
            assertEquals(selection.published, store.selection(torrentId).published)
            assertEquals(setOf("Chosen child"), DocumentsContractTreeAccess(resolver).inspect(grant.toString()).childDisplayNames)
            assertEquals(originalLocation, SaveLocationStore.get(context).read())
        } finally {
            store.cleanupWorkingFiles(torrentId)
            store.remove(torrentId)
            torrentAllocated?.partFile?.delete()
            allocated?.partFile?.delete()
            DC.deleteDocument(resolver, root)
            grants.releaseReadWrite(grant.toString())
            context.revokeUriPermission(grant, permissions)
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
