package com.espitman.sdm.torrent

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.espitman.sdm.domain.Download
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Publish only selected files, never overwrite or delete pre-existing user documents. */
internal object TorrentPublisher {
    suspend fun publish(context: Context, download: Download, contents: TorrentContents, store: TorrentStore): String {
        val root = File(download.destinationPath!!)
        val tree = download.destinationTreeUri ?: return root.path
        val resolver = context.contentResolver
        var selection = store.selection(download.id)
        var rootUri = selection.rootDocument?.let(Uri::parse)
        if (rootUri == null) {
            val treeUri = Uri.parse(tree)
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            rootUri = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, download.fileName)
                ?: error("Cannot create torrent folder. Check folder access.")
            try { selection = store.updateSelection(download.id) { it.copy(rootDocument = rootUri.toString()) } }
            catch (failure: Throwable) { runCatching { DocumentsContract.deleteDocument(resolver, rootUri) }; throw failure }
        }
        val folders = mutableMapOf("" to rootUri)
        fun directory(path: String): Uri {
            folders[path]?.let { return it }
            val parentPath = path.substringBeforeLast('/', "")
            val parent = directory(parentPath)
            val name = path.substringAfterLast('/')
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(parent, DocumentsContract.getDocumentId(parent))
            val existing = resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { cursor ->
                var found: Uri? = null
                while (cursor.moveToNext()) if (cursor.getString(1) == name && cursor.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                    found = DocumentsContract.buildDocumentUriUsingTree(parent, cursor.getString(0))
                found
            }
            val folder = existing ?: DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name)
                ?: error("Cannot create torrent subfolder")
            folders[path] = folder
            return folder
        }
        for (file in contents.files.filter { it.index in selection.selected }) {
            currentCoroutineContext().ensureActive()
            if (file.index in selection.published) continue
            val segments = file.path.split('/')
            // The user-visible job folder already names the torrent, so avoid duplicating its root.
            val relative = if (segments.size > 1 && segments.first() == contents.name) segments.drop(1).joinToString("/") else file.path
            val parent = directory(relative.substringBeforeLast('/', ""))
            val source = TorrentPaths.resolve(root, file.path)
            check(source.isFile && source.length() == file.size) { "Selected torrent file is incomplete" }
            val document = DocumentsContract.createDocument(resolver, parent, "application/octet-stream", relative.substringAfterLast('/'))
                ?: error("Cannot create selected file")
            try {
                resolver.openOutputStream(document, "w")?.use { output -> source.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                } } ?: error("Cannot write selected file")
            } catch (failure: Throwable) { runCatching { DocumentsContract.deleteDocument(resolver, document) }; throw failure }
            selection = store.updateSelection(download.id) { it.copy(published = it.published + (file.index to document.toString())) }
        }
        return rootUri.toString()
    }
}
