package com.espitman.sdm.storage

import android.content.Context
import android.util.AtomicFile
import com.espitman.sdm.data.DownloadRepository
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.torrent.TorrentPaths
import com.espitman.sdm.torrent.TorrentRuntime
import com.espitman.sdm.torrent.TorrentSelection
import com.espitman.sdm.torrent.TorrentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Caller holds FolderMutationGate. Originals remain intact until a verified copy and durable commit plan exist. */
object CompletedTorrentFolderMoveCoordinator {
    data class Result(val name: String, val warning: String? = null)
    suspend fun move(context: Context, id: String, target: CompletedFileMoveCoordinator.Target,
        repository: DownloadRepository): Result = withContext(Dispatchers.IO) {
        val record = repository.get(id) ?: throw IllegalArgumentException("Folder no longer exists")
        require(record.isTorrent && record.state == DownloadState.COMPLETED && TorrentRuntime.telemetry.value[id] == null) {
            "Stop the torrent before moving its completed folder"
        }
        recoverPlans(context, repository, id)
        require(journals(context).listFiles().orEmpty().none { it.name.startsWith("$id-") }) { "The previous move retained changed files or needs folder access. Restore access and try again" }
        val source = record.destinationPath ?: throw IllegalArgumentException("Folder is unavailable")
        val tree = CompletedFolderTree(context)
        val store = TorrentStore(context)
        val originalSelection = store.selection(id)
        val contentSource = DownloadDestinationRef.isContentUri(source)
        if (contentSource) {
            require(originalSelection.rootDocument?.let { tree.identity(it) == tree.identity(source) } == true) { "Torrent folder identity could not be verified" }
            require(DocumentsContractContentDocuments(context).directoryPresence(source, record.destinationTreeUri) == CompletedDestinationPresence.Readable) { "Grant folder access on your phone" }
        } else require(File(source, ".sdm-torrent-owner").takeIf { it.isFile }?.readText() == id) { "Torrent folder identity could not be verified" }
        target.tree?.let { require(PersistableTreeUriGrants(context.contentResolver).hasReadWrite(it)) { "Grant folder access on your phone" } }
        val parent = target.folder?.path ?: target.document?.toString() ?: throw IllegalArgumentException("Folder is unavailable")
        if (!contentSource && target.folder != null && File(source).parentFile?.canonicalFile == target.folder.canonicalFile)
            return@withContext Result(record.fileName)
        val before = tree.snapshot(source)
        require(before.none { it.directory && tree.identity(it.location) == tree.identity(parent) }) { "Choose a destination outside this torrent folder" }
        val contents = TorrentStore.inspect(store.metadata(id))
        val chosen = contents.files.filter { it.index in originalSelection.selected }
        require(chosen.size == originalSelection.selected.size && chosen.sumOf { it.size } == record.downloadedBytes) { "Torrent files are incomplete" }
        val selectedPaths = chosen.associate { file ->
            val location = originalSelection.published[file.index] ?: TorrentPaths.resolve(File(source), file.path).path
            val node = before.singleOrNull { !it.directory && tree.identity(it.location) == tree.identity(location) }
                ?: throw IllegalArgumentException("Selected torrent file is unavailable")
            require(node.size == file.size) { "Selected torrent file is incomplete" }
            file.index to node.path
        }
        val requiredBytes = before.filterNot { it.directory }.fold(0L) { sum, node -> Math.addExact(sum, node.size) }
        val capacity = if (target.folder != null) AndroidStorageCapacityProbe(context).queryLocalPath(target.folder)
            else AndroidStorageCapacityProbe(context).queryTree(target.tree!!)
        capacity.availableBytes?.let { require(it >= requiredBytes) { "Not enough storage in the selected folder" } }
        require(tree.child(parent, record.fileName) == null) { "A file or folder with that name already exists" }
        var destination: String? = null
        var committed = false
        var journal: File? = null
        try {
            destination = tree.create(parent, ".sdm-move-${UUID.randomUUID()}.part", true)
            val paths = mutableMapOf("" to destination!!)
            for (node in before.filter { it.path.isNotEmpty() }.sortedWith(compareBy({ it.path.count { char -> char == '/' } }, { it.path }))) {
                val parentPath = node.path.substringBeforeLast('/', "")
                val child = tree.create(paths.getValue(parentPath), node.path.substringAfterLast('/'), node.directory, node.mime)
                paths[node.path] = child
                if (!node.directory) tree.copy(node, child)
            }
            require(tree.matches(source, before)) { "Torrent folder changed while moving. Try again" }
            if (target.folder != null && tree.child(destination!!, ".sdm-torrent-owner") == null) {
                File(destination!!, ".sdm-torrent-owner").writeText(id)
            }
            if (target.folder != null) require(File(destination!!, ".sdm-torrent-owner").readText() == id) { "Torrent folder identity could not be verified" }
            withContext(NonCancellable) {
                destination = tree.rename(destination!!, parent, record.fileName)
                require(tree.displayName(destination!!) == record.fileName) { "A file or folder with that name already exists" }
                val after = tree.snapshot(destination!!)
                val afterByPath = after.associateBy { it.path }
                require(before.all { node -> afterByPath[node.path]?.let { it.directory == node.directory && it.size == node.size && it.digest == node.digest } == true }) {
                    "Folder copy could not be verified"
                }
                val selection = if (target.folder != null) originalSelection.copy(published = emptyMap(), rootDocument = null)
                    else originalSelection.copy(published = selectedPaths.mapValues { (_, path) -> afterByPath.getValue(path).location }, rootDocument = destination)
                require(repository.get(id) == record) { "Torrent changed while moving. Try again" }
                journal = File(journals(context), "$id-${UUID.randomUUID()}.json")
                write(journal!!, JSONObject().put("id", id).put("source", source).put("destination", destination)
                    .put("metadata", metadataHash(store.metadata(id))).put("before", nodesJson(before)).put("after", nodesJson(after))
                    .put("oldSelection", selectionJson(originalSelection)).put("newSelection", selectionJson(selection)))
                try {
                    store.saveSelection(id, selection)
                    repository.relocateCompleted(id, source, destination!!, target.tree, target.label,
                        maxOf(System.currentTimeMillis(), record.updatedAtEpochMillis))
                    committed = true
                } catch (failure: Throwable) {
                    if (repository.get(id)?.destinationPath == destination) committed = true
                    else { store.saveSelection(id, originalSelection); journal!!.delete() }
                    throw failure
                }
                if (target.folder != null) store.updateWorkingDirectory(id, File(destination!!))
            }
            val removed = runCatching { tree.matches(source, before) && tree.delete(source) }.getOrDefault(false)
            if (removed) journal!!.delete()
            Result(record.fileName, if (removed) null else "Moved to the new folder. The original folder was retained; check its access or changed files.")
        } finally {
            if (!committed) destination?.let { runCatching { tree.delete(it) } }
        }
    }

    /** Recover the record/selection commit before cleanup. Never remove a folder containing changed or added files. */
    suspend fun recover(context: Context, repository: DownloadRepository, downloadId: String? = null): Boolean = withContext(Dispatchers.IO) {
        FolderMutationGate.mutex.withLock {
            recoverPlans(context, repository, downloadId)
        }
    }
    private suspend fun recoverPlans(context: Context, repository: DownloadRepository, downloadId: String?): Boolean {
            val plans = journals(context).listFiles().orEmpty().filter { it.extension == "json" && (downloadId == null || it.name.startsWith("$downloadId-")) }
            if (plans.isEmpty()) return true
            val tree = CompletedFolderTree(context)
            val store = TorrentStore(context)
            var recovered = true
            for (file in plans) {
                val success = runCatching {
                    val plan = JSONObject(String(AtomicFile(file).readFully(), Charsets.UTF_8))
                    val id = plan.getString("id")
                    val record = repository.get(id)
                    if (record == null) { file.delete(); return@runCatching true }
                    require(record.isTorrent && record.state == DownloadState.COMPLETED && metadataHash(store.metadata(id)) == plan.getString("metadata"))
                    val source = plan.getString("source")
                    val destination = plan.getString("destination")
                    when (record.destinationPath) {
                        destination -> {
                            store.saveSelection(id, selectionFrom(plan.getJSONObject("newSelection")))
                            if (!DownloadDestinationRef.isContentUri(destination)) store.updateWorkingDirectory(id, File(destination))
                            if (tree.matches(source, nodesFrom(plan.getJSONArray("before")), allowMissing = true)) {
                                if (!tree.exists(source) || tree.delete(source)) { file.delete(); true } else false
                            } else false
                        }
                        source -> {
                            store.saveSelection(id, selectionFrom(plan.getJSONObject("oldSelection")))
                            if (tree.matches(destination, nodesFrom(plan.getJSONArray("after")), allowMissing = true)) {
                                if (!tree.exists(destination) || tree.delete(destination)) { file.delete(); true } else false
                            } else false
                        }
                        else -> false
                    }
                }.getOrDefault(false)
                recovered = recovered && success
            }
            return recovered
    }
    private fun journals(context: Context) = File(context.filesDir, "completed-folder-moves").apply { check(isDirectory || mkdirs()) }
    private fun metadataHash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun selectionJson(selection: TorrentSelection) = JSONObject().put("selected", JSONArray(selection.selected.sorted()))
        .put("seed", selection.seed).put("root", selection.rootDocument).put("published", JSONObject(selection.published.mapKeys { it.key.toString() }))
    private fun selectionFrom(json: JSONObject): TorrentSelection {
        val selected = json.getJSONArray("selected")
        val published = json.getJSONObject("published")
        return TorrentSelection((0 until selected.length()).map { selected.getInt(it) }.toSet(), json.getBoolean("seed"),
            published.keys().asSequence().associate { it.toInt() to published.getString(it) }, json.optString("root").takeIf { it.isNotEmpty() && it != "null" })
    }
    private fun nodesJson(nodes: List<CompletedFolderTree.Node>) = JSONArray(nodes.map { JSONObject().put("path", it.path)
        .put("location", it.location).put("directory", it.directory).put("size", it.size).put("mime", it.mime).put("digest", it.digest) })
    private fun nodesFrom(json: JSONArray) = (0 until json.length()).map { json.getJSONObject(it).let { node ->
        CompletedFolderTree.Node(node.getString("path"), node.getString("location"), node.getBoolean("directory"), node.getLong("size"), node.getString("mime"), node.getString("digest"))
    } }
    private fun write(file: File, json: JSONObject) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
}
