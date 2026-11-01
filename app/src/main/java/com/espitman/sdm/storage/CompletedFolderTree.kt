package com.espitman.sdm.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** A bounded, symlink-free tree used only for owned completed-download folders. */
internal class CompletedFolderTree(private val context: Context) {
    data class Node(val path: String, val location: String, val directory: Boolean, val size: Long,
        val mime: String, val digest: String = "")
    private val resolver = context.contentResolver
    fun identity(location: String): String = if (DownloadDestinationRef.isContentUri(location)) {
        val uri = Uri.parse(location)
        "${uri.authority}:${DC.getDocumentId(uri)}"
    } else File(location).canonicalPath
    fun exists(root: String): Boolean = if (DownloadDestinationRef.isContentUri(root)) {
        resolver.query(Uri.parse(root), arrayOf(DC.Document.COLUMN_DOCUMENT_ID), null, null, null).use { it != null && it.moveToFirst() }
    } else File(root).exists()
    fun nodes(root: String): List<Node> {
        val result = mutableListOf<Node>()
        val seen = mutableSetOf<String>()
        val paths = mutableSetOf<String>()
        fun add(location: String, path: String, directory: Boolean, size: Long, mime: String) {
            require(path.count { it == '/' } < 128 && result.size < 20_000 && seen.add(identity(location)) && paths.add(path)) { "Folder is too large or contains unsupported links" }
            require(size >= -1) { "File size is unavailable" }
            result += Node(path, location, directory, size, mime)
            if (!directory) return
            if (DownloadDestinationRef.isContentUri(location)) {
                val uri = Uri.parse(location)
                val children = DC.buildChildDocumentsUriUsingTree(uri, DC.getDocumentId(uri))
                resolver.query(children, arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME,
                    DC.Document.COLUMN_MIME_TYPE, DC.Document.COLUMN_SIZE), null, null, null).use { cursor ->
                    require(cursor != null) { "Folder is unavailable" }
                    while (cursor.moveToNext()) {
                        val name = cursor.getString(1)
                        require(validName(name)) { "Folder contains an unsupported file name" }
                        val childMime = cursor.getString(2) ?: "application/octet-stream"
                        val isDirectory = childMime == DC.Document.MIME_TYPE_DIR
                        add(DC.buildDocumentUriUsingTree(uri, cursor.getString(0)).toString(),
                            if (path.isEmpty()) name else "$path/$name", isDirectory,
                            if (isDirectory) 0 else if (cursor.isNull(3)) -1 else cursor.getLong(3), childMime)
                    }
                }
            } else {
                File(location).canonicalFile.listFiles()?.sortedBy { it.name }?.forEach { file ->
                    require(file.canonicalPath == file.absolutePath && validName(file.name) && (file.isFile || file.isDirectory)) {
                        "Folder contains an unsupported link"
                    }
                    add(file.path, if (path.isEmpty()) file.name else "$path/${file.name}", file.isDirectory,
                        if (file.isDirectory) 0 else file.length(), if (file.isDirectory) DC.Document.MIME_TYPE_DIR else "application/octet-stream")
                } ?: throw IllegalArgumentException("Folder is unavailable")
            }
        }
        require(if (DownloadDestinationRef.isContentUri(root)) exists(root) else File(root).isDirectory) { "Folder is unavailable" }
        add(root, "", true, 0, DC.Document.MIME_TYPE_DIR)
        return result.sortedBy { it.path }
    }
    fun input(location: String): InputStream = if (DownloadDestinationRef.isContentUri(location))
        resolver.openInputStream(Uri.parse(location)) ?: throw IllegalArgumentException("File is unavailable") else File(location).inputStream()
    private suspend fun hash(location: String): Pair<String, Long> = input(location).use { input ->
        val hash = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            if (count > 0) { hash.update(buffer, 0, count); bytes = Math.addExact(bytes, count.toLong()) }
        }
        hash.digest().joinToString("") { "%02x".format(it) } to bytes
    }
    suspend fun digest(location: String): String = hash(location).first
    suspend fun snapshot(root: String): List<Node> = nodes(root).map { node ->
        if (node.directory) node else hash(node.location).let { (digest, count) ->
            require(node.size < 0 || node.size == count) { "File changed while reading" }
            node.copy(size = count, digest = digest)
        }
    }
    suspend fun matches(root: String, expected: List<Node>, allowMissing: Boolean = false): Boolean {
        if (!exists(root)) return allowMissing
        val actual = snapshot(root).associateBy { it.path }
        val before = expected.associateBy { it.path }
        return (allowMissing || actual.keys == before.keys) && actual.all { (path, node) ->
            before[path]?.let { it.directory == node.directory && it.size == node.size && it.digest == node.digest } == true
        }
    }
    fun child(parent: String, name: String): String? {
        if (!DownloadDestinationRef.isContentUri(parent)) return File(parent, name).takeIf { it.exists() }?.path
        val uri = Uri.parse(parent)
        resolver.query(DC.buildChildDocumentsUriUsingTree(uri, DC.getDocumentId(uri)),
            arrayOf(DC.Document.COLUMN_DOCUMENT_ID, DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use { cursor ->
            require(cursor != null) { "Folder is unavailable" }
            while (cursor.moveToNext()) if (cursor.getString(1) == name)
                return DC.buildDocumentUriUsingTree(uri, cursor.getString(0)).toString()
        }
        return null
    }
    fun create(parent: String, name: String, directory: Boolean, mime: String = "application/octet-stream"): String {
        require(validName(name)) { "Invalid file name" }
        require(child(parent, name) == null) { "A file or folder with that name already exists" }
        if (!DownloadDestinationRef.isContentUri(parent)) return File(parent, name).also {
            check(if (directory) it.mkdir() else it.createNewFile()) { "Unable to create destination" }
        }.path
        val document = DC.createDocument(resolver, Uri.parse(parent), if (directory) DC.Document.MIME_TYPE_DIR else mime, name)
            ?: throw IllegalArgumentException("Unable to create destination")
        try {
            require(displayName(document.toString()) == name) { "A file or folder with that name already exists" }
        } catch (failure: Throwable) { runCatching { DC.deleteDocument(resolver, document) }; throw failure }
        return document.toString()
    }
    suspend fun copy(source: Node, destination: String) {
        val output = if (DownloadDestinationRef.isContentUri(destination)) resolver.openOutputStream(Uri.parse(destination), "w")
            ?: throw IllegalArgumentException("Unable to write destination") else File(destination).outputStream()
        var copied = 0L
        output.use { out -> input(source.location).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) { out.write(buffer, 0, count); copied += count }
            }
            out.flush()
        } }
        require(copied == source.size && digest(destination) == source.digest) { "File changed or copy could not be verified" }
    }
    fun rename(location: String, parent: String, name: String): String {
        require(child(parent, name) == null) { "A file or folder with that name already exists" }
        if (!DownloadDestinationRef.isContentUri(location)) return File(parent, name).also {
            java.nio.file.Files.move(File(location).toPath(), it.toPath())
        }.path
        return (DC.renameDocument(resolver, Uri.parse(location), name) ?: throw IllegalArgumentException("Unable to finish folder move")).toString()
    }
    fun displayName(location: String): String = if (!DownloadDestinationRef.isContentUri(location)) File(location).name else
        resolver.query(Uri.parse(location), arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null).use {
            require(it != null && it.moveToFirst()) { "Folder is unavailable" }; it.getString(0)
        }
    fun delete(location: String): Boolean = if (DownloadDestinationRef.isContentUri(location)) DC.deleteDocument(resolver, Uri.parse(location))
        else File(location).deleteRecursively()
    private fun validName(name: String) = name.isNotEmpty() && name !in setOf(".", "..") && name.none { it == '/' || it == '\\' || it.isISOControl() }
}
