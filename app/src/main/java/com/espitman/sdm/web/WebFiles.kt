package com.espitman.sdm.web

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.storage.DownloadDestinationRef
import com.espitman.sdm.torrent.TorrentPaths
import com.espitman.sdm.torrent.TorrentStore
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/** File locations come from phone records; the client never supplies a storage path. */
internal class WebFiles(private val context: Context) {
    private data class Entry(val download: Download, val index: Int?, val name: String, val location: String, val size: Long, val mime: String)
    private fun entries(download: Download): List<Entry> {
        if (download.state != DownloadState.COMPLETED) return emptyList()
        val destination = download.destinationPath ?: return emptyList()
        if (!download.isTorrent) return listOf(Entry(download, null, download.fileName, destination,
            download.downloadedBytes, download.mimeType ?: mime(download.fileName)))
        return runCatching {
            val store = TorrentStore(context)
            val selection = store.selection(download.id)
            val contents = TorrentStore.inspect(store.metadata(download.id))
            contents.files.filter { it.index in selection.selected }.map { file ->
                val location = selection.published[file.index] ?: TorrentPaths.resolve(File(destination), file.path).path
                Entry(download, file.index, file.path, location, file.size, mime(file.path))
            }
        }.getOrDefault(emptyList())
    }
    fun identity(download: Download, index: String): com.espitman.sdm.storage.CompletedFileIdentity? = entries(download)
        .firstOrNull { (it.index?.toString() ?: "main") == index && readable(it.location) }
        ?.let { com.espitman.sdm.storage.CompletedFileIdentity(it.download.id,it.location,it.mime,it.name.substringAfterLast('/')) }
    fun canAccess(download: Download, index: String): Boolean = entries(download)
        .firstOrNull { (it.index?.toString() ?: "main") == index }?.let { readable(it.location) } == true
    fun list(records: List<Download>): JSONObject {
        val capacity = com.espitman.sdm.data.AppRepositories.storageCapacity(context).queryActive()
        return JSONObject().put("storage", JSONObject().put("total", capacity.totalBytes ?: JSONObject.NULL)
            .put("available", capacity.availableBytes ?: JSONObject.NULL).put("used", capacity.usedBytes ?: JSONObject.NULL))
            .put("location", com.espitman.sdm.data.AppRepositories.saveLocation(context).current().displayLabel)
            .put("files", JSONArray(records.flatMap(::entries).map { entry ->
                JSONObject().put("id", entry.download.id).put("index", entry.index ?: "main")
                    .put("name", entry.name).put("folder", if (entry.download.isTorrent) entry.download.fileName else JSONObject.NULL)
                    .put("size", entry.size).put("mime", entry.mime).put("preview", canPreview(entry.mime))
                    .put("category", com.espitman.sdm.ui.classifyCompletedFile(entry.mime, entry.name)?.name ?: "Other")
                    .put("modifiedAt", modifiedAt(entry.location) ?: JSONObject.NULL)
                    .put("completedAt", entry.download.completedAtEpochMillis ?: entry.download.updatedAtEpochMillis).put("available", readable(entry.location))
                    .put("renameAllowed", !entry.download.isTorrent).put("torrent", entry.download.isTorrent)
            }))
    }
    fun stream(download: Download, index: String, preview: Boolean, range: String?, browser: WebAccess.Browser): LanHttpServer.Response {
        val entry = entries(download).firstOrNull { (it.index?.toString() ?: "main") == index }
            ?: return WebRouter.json(404, JSONObject().put("error", "File no longer exists"))
        if (!readable(entry.location)) return WebRouter.json(404, JSONObject().put("error", "File is missing or folder access was removed"))
        if (preview && !canPreview(entry.mime)) return WebRouter.json(400, JSONObject().put("error", "Preview is not supported for this file"))
        val span = WebFileRange.parse(range, entry.size)
            ?: return LanHttpServer.Response(416, headers = mapOf("Content-Range" to "bytes */${entry.size}"))
        val input = if (DownloadDestinationRef.isContentUri(entry.location)) context.contentResolver.openInputStream(Uri.parse(entry.location))
            ?: return WebRouter.json(404, JSONObject().put("error", "File is unavailable")) else File(entry.location).inputStream()
        try {
            var remaining = span.start
            while (remaining > 0) {
                val skipped = input.skip(remaining)
                if (skipped > 0) remaining -= skipped else {
                    check(input.read() >= 0) { "File changed while opening" }; remaining--
                }
            }
        } catch (error: Throwable) { input.close(); throw error }
        val safeName = entry.name.substringAfterLast('/')
        val disposition = "${if (preview) "inline" else "attachment"}; filename*=UTF-8''${URLEncoder.encode(safeName, "UTF-8").replace("+", "%20") }"
        val headers = linkedMapOf("Content-Disposition" to disposition, "Accept-Ranges" to "bytes")
        if (preview) headers["Content-Security-Policy"] = "default-src 'none'; img-src 'self' data:; media-src 'self'; frame-ancestors 'self'; base-uri 'none'; form-action 'none'"
        if (range != null) headers["Content-Range"] = "bytes ${span.start}-${span.end}/${entry.size}"
        return LanHttpServer.Response(if (range == null) 200 else 206, if (preview) entry.mime else "application/octet-stream",
            headers = headers, stream = object : InputStream() {
                private var left = span.length
                private fun authorized() {
                    if (WebPanelRuntime.access.browsers.value.none { it.id == browser.id }) { input.close(); throw AccessDenied() }
                }
                override fun read(): Int { authorized(); if (left == 0L) return -1; return input.read().also { if (it >= 0) left-- } }
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    authorized(); if (length == 0) return 0; if (left == 0L) return -1
                    return input.read(buffer, offset, minOf(length.toLong(), left).toInt()).also { if (it > 0) left -= it }
                }
                override fun close() { input.close() }
            }, length = span.length)
    }
    private fun readable(location: String): Boolean {
        if (DownloadDestinationRef.isContentUri(location)) return com.espitman.sdm.storage.ContentResolverCompletedFileProbe(context).isReadableDocument(location)
        val file = File(location)
        val roots = context.getExternalFilesDirs(null).filterNotNull() + context.filesDir
        val canonical = runCatching { file.canonicalPath }.getOrNull() ?: return false
        return roots.any { canonical.startsWith(it.canonicalPath + File.separator) } && file.isFile && file.canRead()
    }
    private fun modifiedAt(location: String): Long? {
        if (!readable(location)) return null
        return runCatching {
            if (DownloadDestinationRef.isContentUri(location)) {
                context.contentResolver.query(Uri.parse(location),
                    arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0).takeIf { it > 0 } else null
                }
            } else File(location).lastModified().takeIf { it > 0 }
        }.getOrNull()
    }
    private fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    private fun canPreview(mime: String) = mime in setOf("image/png", "image/jpeg", "image/webp", "image/gif", "application/pdf", "video/mp4", "video/webm", "audio/mpeg", "audio/mp4", "audio/ogg", "audio/wav")
}
