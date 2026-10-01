package com.espitman.sdm.storage

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as DC
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.SqliteDownloadRepository
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.download.DownloadTransferService
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A durable, idempotent migration. SAF partials stay local and are never copied or discarded. */
class FolderRenameCoordinator(
    context: Context,
    private val repositoryOverride: SqliteDownloadRepository? = null,
    private val schedulerOverride: com.espitman.sdm.download.DownloadQueueScheduler? = null,
    private val storeOverride: SaveLocationStore? = null,
    private val categoriesOverride: CategoryFolderStore? = null,
    private val pauseActive: ((String) -> Unit)? = null,
    private val workersAlive: () -> Boolean = FolderMutationGate::hasWorkers,
) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val resolver = app.contentResolver
    private val repository get() = repositoryOverride ?: AppRepositories.downloads(app) as SqliteDownloadRepository
    private val scheduler get() = schedulerOverride ?: AppRepositories.queueScheduler(app)
    private val store get() = storeOverride ?: SaveLocationStore.get(app)
    private val categories get() = categoriesOverride ?: CategoryFolderStore.get(app)

    var completionMessage: String = "Save folder renamed"
        private set
    fun pendingNotice(): String? = journal()?.let { plan ->
        if (plan.optBoolean("collision")) "A folder with that name already exists. Confirm access to restore the original name."
        else if (plan.optBoolean("rolledBack")) "Original name restored. Confirm folder access to continue downloads."
        else null
    }
    fun pendingTree(): String? = journal()?.let { plan ->
        if (!plan.has("newTree")) null else requiredTrees(plan).firstOrNull { !PersistableTreeUriGrants(resolver).hasReadWrite(it) }
    }
    private fun requiredTrees(plan: JSONObject): List<String> {
        val old = plan.getString("oldTree"); val new = plan.getString("newTree")
        if (plan.optBoolean("collision")) return listOf(new)
        return (listOf(new) + repository.downloads.value.mapNotNull { it.destinationTreeUri } +
            categories.settings.value.rules.values.map { it.treeUri } +
            listOfNotNull(store.read().treeUri))
            .map { remapUri(it, old, new) }.filter { it == new || it != remapUri(it, new, old) }.distinct()
    }
    fun currentName(): String {
        journal()?.let { return it.getString("name") }
        val tree = store.read().treeUri
        return if (tree == null) AppSpecificDownloadsDirectory.folderName(app)
        else queryName(document(Uri.parse(tree))) ?: error("Folder is unavailable. Choose a save location first.")
    }

    suspend fun rename(rawName: String): Boolean = FolderMutationGate.mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            check(journal() == null) { "Finish the pending folder access first." }
            val name = validName(rawName)
            val store = this@FolderRenameCoordinator.store
            val oldTree = store.read().treeUri
            val oldName = currentName()
            if (name == oldName) return@withContext true
            val plan = JSONObject().put("name", name).put("oldName", oldName).put("phase", "prepared")
            if (oldTree == null) {
                val old = AppSpecificDownloadsDirectory.from(app)
                val target = File(old.parentFile, name)
                check(!target.exists()) { "A folder with that name already exists." }
                plan.put("oldPath", old.absolutePath).put("newPath", target.absolutePath)
            } else {
                val tree = Uri.parse(oldTree)
                check(tree.authority == EXTERNAL) { "This storage provider does not support safe folder renaming." }
                val id = DC.getTreeDocumentId(tree)
                check(id.substringAfter(':').contains('/')) { "A storage root cannot be renamed." }
                val flags = queryFlags(document(tree))
                check(flags and DC.Document.FLAG_SUPPORTS_RENAME != 0) { "This folder cannot be renamed by its storage provider." }
                val newId = id.substringBeforeLast('/') + "/" + name
                // ExternalStorageProvider may silently create a unique '(1)' suffix. Directory
                // metadata is available through scoped-storage FUSE even without raw file access.
                val volume = id.substringBefore(':')
                val volumeRoot = if (volume == "primary") android.os.Environment.getExternalStorageDirectory()
                    else app.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
                        val marker = "/Android/data/"; val path = dir.absolutePath
                        if (marker in path) File(path.substringBefore(marker)) else null
                    }.firstOrNull { it.name == volume }
                check(volumeRoot == null || !File(volumeRoot, newId.substringAfter(':')).exists()) { "A folder with that name already exists." }
                plan.put("oldTree", oldTree).put("newTree", DC.buildTreeDocumentUri(tree.authority, newId).toString())
            }
            val snapshot = repository.schedulingSnapshot()
            plan.put("resume", JSONArray(snapshot.filter { it.state in RUNNING }.map { it.id }))
            save(plan) // blocks new transfer starts even if the process is killed
            scheduler.folderMutationBlocked = true
            try {
                scheduler.pauseAll(includeRetries = false) { id -> pauseActive?.invoke(id) ?: DownloadTransferService.pauseTransfer(app, id) }
                withTimeout(30_000) {
                    while (workersAlive() || repository.schedulingSnapshot().any { it.state in ACTIVE }) delay(50)
                }
                if (oldTree == null) {
                    check(File(plan.getString("oldPath")).renameTo(File(plan.getString("newPath")))) { "Could not rename the download folder." }
                } else {
                    val renamed = renameDocument(document(Uri.parse(oldTree)), name)
                        ?: error("The storage provider refused to rename this folder.")
                    val actualId = DC.getDocumentId(renamed)
                    val expectedId = DC.getTreeDocumentId(Uri.parse(plan.getString("newTree")))
                    plan.put("newTree", DC.buildTreeDocumentUri(renamed.authority, actualId).toString())
                    if (actualId != expectedId) plan.put("collision", true).put("name", actualId.substringAfterLast('/'))
                }
                plan.put("phase", "renamed")
                save(plan)
                finishIfAccessible(plan)
            } catch (failure: Exception) {
                // Do not clear a journal when the provider may have already changed the folder.
                if (plan.getString("phase") == "prepared" && originalExists(plan) && !workersAlive() && repository.schedulingSnapshot().none { it.state in ACTIVE }) {
                    clearAndResume(plan)
                }
                throw failure
            }
        }
    }

    /** Re-entered after a picker result, app restart, or a failure between persistent writes. */
    suspend fun recover(selectedTree: String? = null): Boolean = FolderMutationGate.mutex.withLock {
        withContext(Dispatchers.IO + NonCancellable) {
            val plan = journal() ?: return@withContext true
            operationPending = true
            if (plan.has("newTree") && !plan.optBoolean("rolledBack")) {
                val actualName = DC.getTreeDocumentId(Uri.parse(plan.getString("newTree"))).substringAfterLast('/')
                if (actualName != plan.getString("name")) {
                    plan.put("collision", true).put("name", actualName)
                    save(plan)
                }
            }
            scheduler.folderMutationBlocked = true
            // After process death there is no writer; turn stale active states into paused records
            // using their persisted progress, never a sparse segmented file's apparent length.
            if (!workersAlive()) {
                repository.schedulingSnapshot().filter { it.state in ACTIVE }.forEach {
                    repository.requeueInterruptedActive(it.id, maxOf(System.currentTimeMillis(), it.updatedAtEpochMillis))
                }
            }
            scheduler.pauseAll(includeRetries = false) { id -> pauseActive?.invoke(id) ?: DownloadTransferService.pauseTransfer(app, id) }
            withTimeout(30_000) {
                while (workersAlive() || repository.schedulingSnapshot().any { it.state in ACTIVE }) delay(50)
            }
            if (selectedTree != null) {
                check(requiredTrees(plan).any { sameFolder(selectedTree, it) }) { "Select the renamed folder, not another folder." }
                // Keep the exact granted URI, including provider-specific spelling.
                plan.put("phase", "renamed")
                save(plan)
            }
            if (plan.getString("phase") == "prepared" && originalExists(plan)) {
                clearAndResume(plan)
                return@withContext true
            }
            finishIfAccessible(plan)
        }
    }

    private suspend fun finishIfAccessible(plan: JSONObject): Boolean {
        val newTree = plan.optString("newTree").takeIf { it.isNotEmpty() }
        if (newTree != null) {
            if (requiredTrees(plan).any { !PersistableTreeUriGrants(resolver).hasReadWrite(it) }) return false
            check(queryName(document(Uri.parse(newTree))) == plan.getString("name")) { "Renamed folder is unavailable." }
            if (plan.optBoolean("collision")) {
                // The new grant permits reversal; a second picker is needed if reversal changes ID.
                val restored = renameDocument(document(Uri.parse(newTree)), plan.getString("oldName"))
                    ?: error("Could not restore the original folder name. Your files are preserved.")
                val restoredId = DC.getDocumentId(restored)
                val oldId = DC.getTreeDocumentId(Uri.parse(plan.getString("oldTree")))
                plan.put("newTree", DC.buildTreeDocumentUri(restored.authority, restoredId).toString())
                    .put("name", restoredId.substringAfterLast('/')).put("collision", restoredId != oldId)
                    .put("rolledBack", restoredId == oldId)
                save(plan)
                return false
            }
        } else {
            check(File(plan.getString("newPath")).isDirectory) { "Renamed folder is unavailable." }
        }
        repository.remapFolderDestinations { remap(it, plan) }
        if (newTree != null) {
            val store = this@FolderRenameCoordinator.store
            val current = store.read()
            val currentTree = current.treeUri?.let { remapUri(it, plan.getString("oldTree"), newTree) }
            if (currentTree != null && (currentTree != current.treeUri || sameFolder(currentTree, newTree))) {
                store.persistUserTree(currentTree, SaveLocationLabels.fromTree(currentTree, plan.getString("name")))
            }
            val categories = this@FolderRenameCoordinator.categories
            val settings = categories.settings.value
            categories.replace(settings.copy(rules = settings.rules.mapValues { (_, rule) ->
                val mapped = remapUri(rule.treeUri, plan.getString("oldTree"), newTree)
                if (mapped != rule.treeUri || sameFolder(mapped, newTree)) rule.copy(treeUri = mapped, label = SaveLocationLabels.fromTree(mapped, if (sameFolder(mapped, newTree)) plan.getString("name") else null)) else rule
            }))
        } else {
            check(app.getSharedPreferences("sdm_settings", Context.MODE_PRIVATE).edit()
                .putString("app_download_folder_name", plan.getString("name")).commit()) { "Could not save folder name." }
            store.persistAppSpecific()
        }
        if (plan.optBoolean("rolledBack")) completionMessage = "Original folder restored; the requested name already exists"
        clearAndResume(plan)
        return true
    }

    private suspend fun clearAndResume(plan: JSONObject) {
        // Keep a replayable journal until records have been restored. No transfers can start yet.
        val ids = plan.getJSONArray("resume")
        for (index in 0 until ids.length()) {
            val id = ids.getString(index)
            val download = repository.get(id)
            if (download?.state == DownloadState.PAUSED) repository.resumePaused(id, maxOf(System.currentTimeMillis(), download.updatedAtEpochMillis))
        }
        check(prefs.edit().remove(KEY).commit()) { "Could not finish folder migration." }
        operationPending = false
        scheduler.folderMutationBlocked = false
        scheduler.schedule()
    }

    private fun originalExists(plan: JSONObject): Boolean = try {
        if (plan.has("oldPath")) File(plan.getString("oldPath")).isDirectory && !File(plan.getString("newPath")).exists()
        else queryName(document(Uri.parse(plan.getString("oldTree")))) == plan.getString("oldName")
    } catch (_: Exception) { false }

    private fun journal(): JSONObject? = prefs.getString(KEY, null)?.let(::JSONObject)
    private fun save(plan: JSONObject) {
        check(prefs.edit().putString(KEY, plan.toString()).commit()) { "Could not save folder migration." }
        operationPending = true
    }
    private fun renameDocument(uri: Uri, name: String): Uri? = try {
        DC.renameDocument(resolver, uri, name)
    } catch (failure: Exception) {
        throw IllegalStateException("Could not rename this folder. Check folder access and try again.", failure)
    }
    private fun queryName(uri: Uri): String? = try {
        resolver.query(uri, arrayOf(DC.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    } catch (_: Exception) { null }
    private fun queryFlags(uri: Uri): Int = try {
        resolver.query(uri, arrayOf(DC.Document.COLUMN_FLAGS), null, null, null)?.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        } ?: 0
    } catch (_: Exception) { 0 }

    companion object {
        private const val PREFS = "sdm_folder_migration"
        private const val KEY = "journal"
        private const val EXTERNAL = "com.android.externalstorage.documents"
        private val ACTIVE = setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)
        private val RUNNING = ACTIVE + DownloadState.QUEUED
        @Volatile var operationPending = false
            private set
        fun isPending(context: Context): Boolean = (context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY)).also { operationPending = it }
        fun validName(raw: String): String = raw.trim().also {
            require(it.isNotEmpty() && it != "." && it != ".." && it.length <= 120 && it.toByteArray(Charsets.UTF_8).size <= 240 && !it.endsWith(".") && it.none { c -> c in "/\\:*?\"<>|" || c.isISOControl() }) { "Enter a folder name without slashes or special control characters." }
            require(it != SaveLocationDestinationAllocator.STAGING_DIRECTORY_NAME) { "Choose a different folder name." }
        }
        private fun document(tree: Uri) = DC.buildDocumentUriUsingTree(tree, DC.getTreeDocumentId(tree))
        fun sameFolder(a: String, b: String): Boolean = try {
            val first = Uri.parse(a); val second = Uri.parse(b)
            first.authority == second.authority && DC.getTreeDocumentId(first) == DC.getTreeDocumentId(second)
        } catch (_: Exception) { false }
        fun remapUri(value: String, oldTree: String, newTree: String): String {
            val uri = Uri.parse(value); val old = Uri.parse(oldTree); val new = Uri.parse(newTree)
            if (uri.authority != old.authority || !DC.isTreeUri(uri)) return value
            val oldId = DC.getTreeDocumentId(old); val newId = DC.getTreeDocumentId(new)
            fun mapped(id: String) = if (id == oldId) newId else if (id.startsWith("$oldId/")) newId + id.removePrefix(oldId) else id
            val treeId = DC.getTreeDocumentId(uri)
            val mappedTree = DC.buildTreeDocumentUri(uri.authority, mapped(treeId))
            return if (uri.pathSegments.contains("document")) DC.buildDocumentUriUsingTree(mappedTree, mapped(DC.getDocumentId(uri))).toString() else mappedTree.toString()
        }
        fun remap(download: Download, plan: JSONObject): Download {
            if (plan.has("oldPath")) {
                val old = plan.getString("oldPath"); val new = plan.getString("newPath")
                val path = download.destinationPath ?: return download
                return if (path.startsWith("$old/")) download.copy(destinationPath = new + path.removePrefix(old)) else download
            }
            val old = plan.getString("oldTree"); val new = plan.getString("newTree")
            val tree = download.destinationTreeUri?.let { remapUri(it, old, new) }
            val path = download.destinationPath?.let { if (DownloadDestinationRef.isContentUri(it)) remapUri(it, old, new) else it }
            val affected = tree != download.destinationTreeUri || path != download.destinationPath
            return if (affected) download.copy(destinationPath = path, destinationTreeUri = tree,
                destinationDisplayLabel = tree?.let { SaveLocationLabels.fromTree(it, if (sameFolder(it, new)) plan.getString("name") else null) }) else download
        }
    }
}
