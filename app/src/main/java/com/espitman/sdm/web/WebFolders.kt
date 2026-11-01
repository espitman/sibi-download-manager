package com.espitman.sdm.web

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import com.espitman.sdm.storage.AppSpecificDownloadsDirectory
import com.espitman.sdm.storage.PersistableTreeUriGrants
import com.espitman.sdm.storage.SaveLocationDestinationAllocator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Opaque browser-owned folder references. Paths and URI grants never come from the browser. */
internal class WebFolders(private val context: Context) {
    data class Folder(val file: File? = null, val tree: String? = null, val document: Uri? = null,
        val name: String, val root: String, val parent: String? = null)
    private data class Reference(val owner: String, val folder: Folder, var expires: Long)
    private val references = linkedMapOf<String, Reference>()
    @Synchronized fun expire(approved: Set<String>) {
        val now = System.currentTimeMillis()
        references.entries.removeAll { it.value.owner !in approved || it.value.expires <= now }
    }
    @Synchronized private fun token(owner: String, folder: Folder): String {
        val now = System.currentTimeMillis()
        references.entries.removeAll { it.value.expires <= now }
        references.entries.firstOrNull { it.value.owner == owner && it.value.folder == folder }?.let {
            it.value.expires = now + 600_000; return it.key
        }
        require(references.size < 4096) { "Too many open folders. Close and reopen the folder picker" }
        return UUID.randomUUID().toString().also { references[it] = Reference(owner, folder, now + 600_000) }
    }
    @Synchronized fun resolve(owner: String, id: String): Folder {
        val reference = references[id]?.takeIf { it.owner == owner && it.expires > System.currentTimeMillis() }
            ?: throw IllegalArgumentException("Folder selection expired. Choose the folder again")
        reference.expires = System.currentTimeMillis() + 600_000
        val folder = reference.folder
        folder.file?.let { file ->
            val root = AppSpecificDownloadsDirectory.from(context).canonicalFile
            val canonical = file.canonicalFile
            val staging = File(root, SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME)
            require((canonical == root || canonical.path.startsWith(root.path + File.separator)) && file.isDirectory && file.canWrite()) {
                "Folder is unavailable"
            }
            require(canonical != staging && !canonical.path.startsWith(staging.path + File.separator)) { "Folder is unavailable" }
        }
        folder.tree?.let {
            require(PersistableTreeUriGrants(context.contentResolver).hasReadWrite(it)) { "Folder access was removed. Grant access on your phone" }
            require(com.espitman.sdm.storage.DocumentsContractContentDocuments(context).directoryPresence(folder.document.toString(), it) ==
                com.espitman.sdm.storage.CompletedDestinationPresence.Readable) { "Folder is unavailable" }
        }
        return folder
    }
    fun allocator(owner: String, id: String): com.espitman.sdm.storage.DestinationAllocator =
        com.espitman.sdm.storage.DestinationAllocator { name ->
            val folder = resolve(owner, id)
            if (folder.file != null) {
                com.espitman.sdm.storage.AppPrivateDestinationAllocator({ folder.file }, folder.name).allocate(name)
            } else {
                val tree = requireNotNull(folder.tree)
                val parent = requireNotNull(folder.document).toString()
                val inspection = com.espitman.sdm.storage.DocumentsContractTreeAccess(context.contentResolver).inspectDirectory(tree, parent)
                require(inspection.state == com.espitman.sdm.storage.UserTreeState.Writable) {
                    "Folder is not writable. Check access on your phone"
                }
                val staging = SaveLocationDestinationAllocator.stagingDirectory(AppSpecificDownloadsDirectory.from(context))
                com.espitman.sdm.storage.AppPrivateDestinationAllocator.reserveInDirectory(
                    staging, name, inspection.childDisplayNames + staging.list().orEmpty(), tree, folder.name, parent)
            }
        }

    fun list(owner: String, id: String?): JSONObject {
        if (id == null) {
            val app = AppSpecificDownloadsDirectory.from(context)
            val roots = mutableListOf(Folder(file = app, name = "App / ${app.name}", root = app.path))
            for (tree in PersistableTreeUriGrants(context.contentResolver).persistedReadWriteTrees()) {
                val uri = Uri.parse(tree)
                val document = DC.buildDocumentUriUsingTree(uri, DC.getTreeDocumentId(uri))
                val name = runCatching { context.contentResolver.query(document, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
                    if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else null
                } }.getOrNull() ?: "Selected phone folder"
                roots += Folder(tree = tree, document = document, name = name, root = tree)
            }
            return JSONObject().put("name", "Phone").put("parent", JSONObject.NULL).put("current", JSONObject.NULL)
                .put("folders", JSONArray(roots.map { json(owner, it).put("kind", if (it.file != null) "app" else "selected") }))
        }
        val folder = resolve(owner, id)
        val children = if (folder.file != null) {
            folder.file.listFiles()?.filter { it.isDirectory && it.canonicalPath == it.absolutePath &&
                !(folder.file == AppSpecificDownloadsDirectory.from(context) && it.name == SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME) }?.sortedBy { it.name.lowercase() }
                ?.take(501)?.map { Folder(file = it, name = it.name, root = folder.root, parent = id) } ?: throw IllegalArgumentException("Folder is unavailable")
        } else {
            val parent = folder.document!!
            val uri = DC.buildChildDocumentsUriUsingTree(parent, DC.getDocumentId(parent))
            val result = mutableListOf<Folder>()
            context.contentResolver.query(uri, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME, DC.Document.COLUMN_MIME_TYPE), null, null, null).use { cursor ->
                require(cursor != null) { "Folder is unavailable" }
                while (cursor.moveToNext() && result.size <= 500) if (cursor.getString(2) == DC.Document.MIME_TYPE_DIR) {
                    result += Folder(tree = folder.tree, document = DC.buildDocumentUriUsingTree(parent, cursor.getString(0)),
                        name = cursor.getString(1), root = folder.root, parent = id)
                }
            }
            result.sortedBy { it.name.lowercase() }
        }
        return JSONObject().put("name", folder.name).put("current", id).put("parent", folder.parent ?: JSONObject.NULL)
            .put("truncated", children.size > 500).put("folders", JSONArray(children.take(500).map { json(owner, it) }))
    }
    fun create(owner: String, parent: String, rawName: String): JSONObject {
        val name = rawName.trim()
        require(name.isNotEmpty() && name.length <= 120 && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }) { "Enter a valid folder name" }
        val folder = resolve(owner, parent)
        require(folder.file != AppSpecificDownloadsDirectory.from(context) || name != SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME) {
            "This folder name is reserved"
        }
        val child = if (folder.file != null) {
            val file = File(folder.file, name)
            require(!file.exists()) { "A folder with that name already exists" }
            check(file.mkdir()) { "Unable to create folder" }
            Folder(file = file, name = name, root = folder.root, parent = parent)
        } else {
            val uri = DC.buildChildDocumentsUriUsingTree(folder.document, DC.getDocumentId(folder.document))
            context.contentResolver.query(uri, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
                require(cursor != null) { "Folder is unavailable" }
                while (cursor.moveToNext()) require(cursor.getString(0) != name) { "A folder with that name already exists" }
            }
            val document = DC.createDocument(context.contentResolver, folder.document!!, DC.Document.MIME_TYPE_DIR, name)
                ?: throw IllegalArgumentException("Unable to create folder")
            Folder(tree = folder.tree, document = document, name = name, root = folder.root, parent = parent)
        }
        return json(owner, child)
    }
    private fun json(owner: String, folder: Folder) = JSONObject().put("id", token(owner, folder)).put("name", folder.name)
}
