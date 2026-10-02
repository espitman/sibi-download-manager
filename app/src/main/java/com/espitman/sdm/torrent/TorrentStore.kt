package com.espitman.sdm.torrent

import android.content.Context
import android.util.AtomicFile
import com.frostwire.jlibtorrent.FileStorage
import com.frostwire.jlibtorrent.TorrentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class TorrentFile(val index: Int, val path: String, val size: Long)
data class TorrentContents(val name: String, val magnet: String, val metadata: ByteArray, val files: List<TorrentFile>)
data class TorrentSelection(val selected: Set<Int>, val seed: Boolean = false, val published: Map<Int, String> = emptyMap(), val rootDocument: String? = null)

class TorrentStore(context: Context) {
    init { TorrentNativeRuntime.prepare(context) }
    private val root = File(context.filesDir, "torrent-jobs")
    fun directory(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9-]+"))) { "Invalid torrent job ID" }
        return File(root, id)
    }
    fun create(id: String, contents: TorrentContents, selection: TorrentSelection, workingDirectory: File? = null) {
        val directory = directory(id)
        check(directory.mkdirs()) { "Torrent job already exists" }
        try {
            atomicWrite(File(directory, "metadata.torrent"), contents.metadata)
            saveSelection(id, selection)
            workingDirectory?.let {
                File(it, ".sdm-torrent-owner").writeText(id)
                atomicWrite(File(directory, "working-path"), it.canonicalPath.toByteArray())
            }
        } catch (failure: Exception) { directory.deleteRecursively(); throw failure }
    }
    fun metadata(id: String) = File(directory(id), "metadata.torrent").readBytes()
    fun selection(id: String): TorrentSelection {
        val json = JSONObject(String(AtomicFile(File(directory(id), "selection.json")).readFully(), Charsets.UTF_8))
        val array = json.getJSONArray("selected")
        val published = json.optJSONObject("published") ?: JSONObject()
        return TorrentSelection((0 until array.length()).map { array.getInt(it) }.toSet(), json.optBoolean("seed"),
            published.keys().asSequence().associate { it.toInt() to published.getString(it) }, json.optString("rootDocument").takeIf { it.isNotEmpty() && it != "null" })
    }
    fun updateSelection(id: String, change: (TorrentSelection) -> TorrentSelection): TorrentSelection = synchronized(lock) {
        change(selection(id)).also { saveSelection(id, it) }
    }
    fun updateWorkingDirectory(id: String, folder: File) {
        if (File(folder, ".sdm-torrent-owner").takeIf { it.isFile }?.readText() == id)
            atomicWrite(File(directory(id), "working-path"), folder.canonicalPath.toByteArray())
    }
    fun cleanupWorkingFiles(id: String) {
        val path = File(directory(id), "working-path").takeIf { it.isFile }?.readText() ?: return
        val folder = File(path)
        // Only the fresh per-job directory recorded at allocation belongs to this job.
        if (folder.isDirectory && folder.canonicalPath == path && File(folder, ".sdm-torrent-owner").takeIf { it.isFile }?.readText() == id) folder.deleteRecursively()
    }
    fun saveSelection(id: String, selection: TorrentSelection) = synchronized(lock) {
        atomicWrite(File(directory(id), "selection.json"), JSONObject().put("selected", JSONArray(selection.selected.sorted()))
            .put("rootDocument", selection.rootDocument).put("seed", selection.seed).put("published", JSONObject(selection.published.mapKeys { it.key.toString() })).toString().toByteArray())
    }
    fun remove(id: String) { directory(id).deleteRecursively() }
    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) } catch (failure: Exception) { atomic.failWrite(output); throw failure }
    }
    companion object {
        private val lock = Any()
        const val MAX_METADATA_BYTES = 8 * 1024 * 1024
        fun inspect(bytes: ByteArray): TorrentContents {
            require(bytes.size in 1..MAX_METADATA_BYTES) { "Torrent metadata exceeds 8 MB" }
            val info = TorrentInfo(bytes)
            require(info.isValid && info.numFiles() in 1..5000) { "Invalid torrent or too many files" }
            val files = info.files()
            val rows = (0 until info.numFiles()).mapNotNull { index ->
                val flags = files.fileFlags(index)
                require(flags.and_(FileStorage.FLAG_SYMLINK).to_int() == 0 && !files.fileAbsolutePath(index)) { "Torrent symlinks are not supported" }
                val path = TorrentPaths.validate(files.filePath(index))
                if (flags.and_(FileStorage.FLAG_PAD_FILE).to_int() != 0) null else TorrentFile(index, path, files.fileSize(index))
            }
            require(rows.isNotEmpty() && rows.all { it.size >= 0 } && rows.map { it.path }.distinct().size == rows.size) { "Invalid torrent file list" }
            require(rows.fold(0L) { total, file -> Math.addExact(total, file.size) } >= 0)
            val name = com.espitman.sdm.network.DownloadFilenameResolver.resolveFilename(null, null, info.name())
            return TorrentContents(name, info.makeMagnetUri(), bytes, rows)
        }
    }
}
