package com.espitman.sdm.web

import android.content.Context
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.data.settings.automaticRetry
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.download.HttpConnectionTelemetry
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.ui.RecentTransferSpeedTracker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** Commands always use the application's authoritative repositories and coordinators. */
class WebAppBridge(context: Context) {
    private val context = context.applicationContext
    private val repository = AppRepositories.downloads(this.context)
    private val speeds = RecentTransferSpeedTracker()
    private val torrents = WebTorrentDrafts()
    private val folders = WebFolders(this.context)
    private val storageSettings = WebStorageSettings(this.context, folders)
    private val backups = WebBackups(this.context)
    private val linkRefresh = WebLinkRefresh(this.context)
    private val bulkLinkRefresh = WebBulkLinkRefresh(this.context)
    private val checksumGate = kotlinx.coroutines.sync.Mutex()
    private val moveRecovery = kotlinx.coroutines.sync.Mutex()
    private var movesRecovered = false
    fun expireDrafts() { val approved = WebPanelRuntime.access.browsers.value.map { it.id }.toSet(); torrents.expire(approved); folders.expire(approved); linkRefresh.expire(approved); bulkLinkRefresh.expire(approved); backups.expire(approved); WebPanelRuntime.folderAccess.expire(); WebPanelRuntime.fileHandoff.expire() }
    fun close() = torrents.close()
    fun handle(request: LanHttpServer.Request, browser: WebAccess.Browser): LanHttpServer.Response = runBlocking(Dispatchers.IO) {
        repository.awaitInitialized()
        moveRecovery.withLock {
            if (!movesRecovered) {
                com.espitman.sdm.storage.CompletedTorrentFolderMoveCoordinator.recover(context, repository)
                movesRecovered = true
            }
        }
        if (request.method == "POST") {
            val key = request.headers["idempotency-key"] ?: throw IllegalArgumentException("Command key is required")
            val fingerprint = java.security.MessageDigest.getInstance("SHA-256")
                .digest((request.path + "\n").toByteArray() + request.body).joinToString("") { "%02x".format(it) }
            WebPanelRuntime.commands.execute(browser.id, key, fingerprint) {
                if (WebPanelRuntime.access.browsers.value.none { it.id == browser.id }) throw AccessDenied()
                dispatch(request, browser)
            }
        } else dispatch(request, browser)
    }
    private suspend fun dispatch(request: LanHttpServer.Request, browser: WebAccess.Browser): LanHttpServer.Response {
        return when {
            request.method == "GET" && request.path == "/api/state" -> WebRouter.json(value = snapshot())
            request.method == "GET" && request.path == "/api/panel" -> {
                val status = WebPanelRuntime.status.value
                WebRouter.json(value = JSONObject().put("port", status.port).put("running", status.running)
                    .put("address", status.address ?: JSONObject.NULL).put("error", status.error ?: JSONObject.NULL))
            }
            request.method == "POST" && request.path == "/api/panel/control" -> {
                val body = WebRouter.body(request)
                require(body.optBoolean("confirmed")) { "Confirm the panel change first" }
                val before = WebPanelRuntime.status.value
                require(before.running && before.port == body.getInt("expectedPort")) { "Panel settings changed on your phone. Refresh and try again" }
                val action = body.getString("action")
                require(action in setOf("port", "stop")) { "Unknown panel action" }
                val port = if (action == "port") body.getInt("port") else before.port
                require(port in 1024..65535) { "Choose a port from 1024 to 65535" }
                require(action != "port" || body.get("port").toString() == port.toString()) { "Enter a whole port number" }
                val host = java.net.URI(requireNotNull(before.address)).host
                if (action == "port" && port != before.port) {
                    try { java.net.ServerSocket().use { it.bind(java.net.InetSocketAddress(host, port)) } }
                    catch (_: java.io.IOException) { throw IllegalArgumentException("This port is already in use. Choose another port") }
                }
                val sent = java.util.concurrent.atomic.AtomicBoolean()
                WebRouter.json(value = JSONObject().put("accepted", true).put("action", action)
                    .put("address", "http://$host:$port").put("previousAddress", before.address)).copy(afterSent = {
                    if (sent.compareAndSet(false, true) && WebPanelRuntime.access.browsers.value.any { it.id == browser.id } &&
                        WebPanelRuntime.status.value.address == before.address && WebPanelRuntime.enabled(context)) {
                        if (action == "stop") WebPanelRuntime.stop(context) else WebPanelRuntime.setPort(context, port)
                    }
                })
            }
            request.method == "GET" && request.path.startsWith("/api/torrent/details/") -> {
                val id = request.path.removePrefix("/api/torrent/details/")
                val record = repository.get(id) ?: throw IllegalArgumentException("Download no longer exists")
                require(record.isTorrent) { "This is not a torrent download" }
                val store = com.espitman.sdm.torrent.TorrentStore(context)
                val contents = com.espitman.sdm.torrent.TorrentStore.inspect(store.metadata(id))
                val selection = store.selection(id)
                val info = com.frostwire.jlibtorrent.TorrentInfo(contents.metadata)
                WebRouter.json(value = JSONObject().put("id", id).put("name", contents.name).put("magnet", contents.magnet)
                    .put("hash", info.infoHashV1().toHex()).put("total", contents.files.sumOf { it.size })
                    .put("seed", selection.seed).put("trackers", JSONArray(info.trackers().take(200).map { it.url() }))
                    .put("files", JSONArray(contents.files.map { JSONObject().put("index", it.index).put("path", it.path)
                        .put("size", it.size).put("selected", it.index in selection.selected) })))
            }
            request.method == "POST" && request.path == "/api/torrent/seed" -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                val record = repository.get(id) ?: throw IllegalArgumentException("Download no longer exists")
                require(record.isTorrent && record.state != DownloadState.COMPLETED) { "Seeding cannot be changed for this download" }
                val seed = body.getBoolean("seed")
                com.espitman.sdm.torrent.TorrentStore(context).updateSelection(id) { it.copy(seed = seed) }
                WebRouter.json(value = JSONObject().put("seed", seed))
            }
            request.method == "POST" && request.path == "/api/torrent/resolve" -> {
                val link = WebRouter.body(request).getString("link").trim()
                require(link.length in 1..8192) { "Enter a magnet link or torrent URL" }
                require(com.espitman.sdm.torrent.TorrentMagnet.isValid(link) || com.espitman.sdm.domain.DownloadUrl.validate(link) is com.espitman.sdm.domain.DownloadUrlResult.Valid) { "Enter a magnet link or torrent URL" }
                val id = torrents.start(browser.id) { com.espitman.sdm.torrent.TorrentResolver.link(context, link) }
                WebRouter.json(202, JSONObject().put("id", id))
            }
            request.method == "POST" && request.path == "/api/torrent/upload" -> {
                require(request.headers["content-type"] == "application/x-bittorrent") { "Torrent content type required" }
                require(request.body.size in 1..com.espitman.sdm.torrent.TorrentStore.MAX_METADATA_BYTES) { "Choose a torrent file up to 8 MB" }
                val id = torrents.start(browser.id) {
                    com.espitman.sdm.torrent.TorrentNativeRuntime.prepare(context)
                    com.espitman.sdm.torrent.TorrentStore.inspect(request.body)
                }
                WebRouter.json(202, JSONObject().put("id", id))
            }
            request.method == "GET" && request.path.startsWith("/api/torrent/draft/") ->
                WebRouter.json(value = torrents.status(browser.id, request.path.removePrefix("/api/torrent/draft/")))
            request.method == "POST" && request.path == "/api/torrent/cancel" -> {
                torrents.remove(browser.id, WebRouter.body(request).getString("id"))
                WebRouter.json(value = JSONObject().put("ok", true))
            }
            request.method == "POST" && request.path == "/api/torrent/submit" -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                val array = body.getJSONArray("selected")
                require(array.length() in 1..5000) { "Select at least one file" }
                val selected = (0 until array.length()).map { index ->
                    val value = array.get(index)
                    require(value is Number && value.toDouble() == value.toInt().toDouble()) { "Invalid file selection" }
                    value.toInt()
                }.toSet()
                require(selected.size == array.length()) { "Duplicate file selection" }
                val download = torrents.submit(browser.id, id) { contents ->
                    com.espitman.sdm.torrent.TorrentSubmission.submit(context, contents, selected,
                        body.getBoolean("seed"), body.getBoolean("startNow"),
                        deferUntilResumed = !body.getBoolean("startNow"),
                        destinationAllocatorOverride = body.optString("folder").takeIf { it.isNotBlank() }?.let { folders.allocator(browser.id, it) })
                }
                WebRouter.json(value = JSONObject().put("id", download.id).put("name", download.fileName))
            }
            request.method == "GET" && request.path == "/api/settings/storage" -> WebRouter.json(value = storageSettings.snapshot())
            request.method == "POST" && request.path == "/api/settings/storage/request" -> {
                check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Finish the pending folder rename first" }
                val body = WebRouter.body(request)
                WebRouter.json(value = WebPanelRuntime.folderAccess.create(browser.id, body.optString("category").takeIf { it.isNotBlank() }))
            }
            request.method == "GET" && request.path.startsWith("/api/settings/storage/request/") ->
                WebRouter.json(value = WebPanelRuntime.folderAccess.status(browser.id, request.path.substringAfterLast('/')))
            request.method == "POST" && request.path == "/api/settings/storage/request/cancel" -> {
                WebPanelRuntime.folderAccess.cancel(browser.id, WebRouter.body(request).getString("id"))
                WebRouter.json(value = JSONObject().put("cancelled", true))
            }
            request.method == "POST" && request.path == "/api/settings/storage/rename" ->
                WebRouter.json(value = storageSettings.rename(WebRouter.body(request)))
            request.method == "POST" && request.path == "/api/settings/storage" -> {
                val body = WebRouter.body(request)
                com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                    WebRouter.json(value = storageSettings.update(browser.id, body))
                }
            }
            request.method == "GET" && request.path == "/api/files" -> WebRouter.json(value = WebFiles(context).list(repository.downloads.value))
            request.method == "GET" && request.path == "/api/folders" -> WebRouter.json(value = folders.list(browser.id, null))
            request.method == "GET" && request.path.startsWith("/api/folders/") -> WebRouter.json(value = folders.list(browser.id, request.path.removePrefix("/api/folders/")))
            request.method == "POST" && request.path == "/api/folders/create" -> {
                val body = WebRouter.body(request)
                com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                    check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
                    WebRouter.json(value = folders.create(browser.id, body.getString("parent"), body.getString("name")))
                }
            }
            request.method == "POST" && request.path == "/api/files/phone" -> {
                val body=WebRouter.body(request)
                val download=repository.get(body.getString("id")) ?: throw IllegalArgumentException("File no longer exists")
                val identity=WebFiles(context).identity(download,body.getString("index")) ?: throw IllegalArgumentException("File is unavailable")
                val action=when(body.getString("action")) {
                    "open" -> com.espitman.sdm.storage.CompletedFileAction.Open
                    "share" -> com.espitman.sdm.storage.CompletedFileAction.Share
                    else -> throw IllegalArgumentException("Unknown file action")
                }
                val pending=WebPanelRuntime.fileHandoff.request(browser.id,browser.browser,identity,action,body.getString("index"))
                WebRouter.json(value=JSONObject().put("id",pending.id).put("message","Open SDM on your phone to confirm this file request"))
            }
            request.method == "POST" && request.path == "/api/files/move" -> {
                val body = WebRouter.body(request)
                val array = body.getJSONArray("ids")
                require(array.length() in 1..100) { "Select between 1 and 100 files" }
                val ids = (0 until array.length()).map { array.getString(it) }.toSet()
                require(ids.size == array.length()) { "Duplicate file selection" }
                val results = JSONArray()
                com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                    check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
                    val folder = folders.resolve(browser.id, body.getString("folder"))
                    for (id in ids) {
                        val record = repository.get(id)
                        try {
                            require(record != null && (record.isTorrent || WebFiles(context).canAccess(record, "main"))) { "File is unavailable" }
                            val target = com.espitman.sdm.storage.CompletedFileMoveCoordinator.Target(folder.file, folder.tree, folder.document, folder.name)
                            val moved = if (record.isTorrent) com.espitman.sdm.storage.CompletedTorrentFolderMoveCoordinator.move(context, id, target, repository)
                                else com.espitman.sdm.storage.CompletedTorrentFolderMoveCoordinator.Result(
                                    com.espitman.sdm.storage.CompletedFileMoveCoordinator.move(context, id, target, repository))
                            results.put(JSONObject().put("id", id).put("moved", true).put("name", moved.name).put("warning", moved.warning))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                        catch (failure: Exception) { results.put(JSONObject().put("id", id).put("moved", false).put("error",
                            com.espitman.sdm.domain.ErrorReportSanitizer.sanitize(failure.message, 160).ifBlank { "Unable to move file. Check folder access and storage" })) }
                    }
                }
                WebRouter.json(value = JSONObject().put("results", results))
            }
            request.method == "POST" && request.path in setOf("/api/files/rename", "/api/downloads/rename") -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                val record = repository.get(id) ?: throw IllegalArgumentException("File no longer exists")
                if (request.path == "/api/files/rename") require(record.state == DownloadState.COMPLETED) { "File is not completed" }
                require(!record.isTorrent) { "Torrent file names are fixed by their metadata" }
                if (record.state == DownloadState.COMPLETED) require(WebFiles(context).canAccess(record, "main")) { "File is missing or folder access was removed" }
                val result = com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                    check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
                    com.espitman.sdm.download.DownloadRenameCoordinator.rename(id, body.getString("name"), repository,
                        com.espitman.sdm.download.Clock.SystemClock, com.espitman.sdm.storage.DocumentsContractContentDocuments(context))
                }
                when (result) {
                    is com.espitman.sdm.download.DownloadRenameResult.Success -> WebRouter.json(value = JSONObject().put("name", result.download.fileName))
                    is com.espitman.sdm.download.DownloadRenameResult.Failure -> WebRouter.json(409, JSONObject().put("error", result.message))
                    is com.espitman.sdm.download.DownloadRenameResult.PauseRequired -> WebRouter.json(409, JSONObject().put("error", result.message))
                }
            }
            request.method == "POST" && request.path == "/api/files/delete" -> {
                val body = WebRouter.body(request)
                require(body.getBoolean("confirmed")) { "Confirm permanent file deletion first" }
                val array = body.getJSONArray("ids")
                require(array.length() in 1..100) { "Select between 1 and 100 files" }
                val ids = (0 until array.length()).map { array.getString(it) }.toSet()
                require(ids.size == array.length()) { "Duplicate file selection" }
                val results = JSONArray()
                com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                    check(!com.espitman.sdm.storage.FolderRenameCoordinator.isPending(context)) { "Wait for folder changes to finish" }
                    for (id in ids) {
                        val record = repository.get(id)
                        if (record != null && record.state != DownloadState.COMPLETED) {
                            results.put(JSONObject().put("id", id).put("deleted", false).put("error", "File is not completed")); continue
                        }
                        val result = com.espitman.sdm.download.CompletedFileDeleteCoordinator.delete(id, repository,
                            com.espitman.sdm.storage.DocumentsContractContentDocuments(context))
                        results.put(JSONObject().put("id", id).put("deleted", result !is com.espitman.sdm.download.CompletedFileDeleteResult.Failure)
                            .put("error", if (result is com.espitman.sdm.download.CompletedFileDeleteResult.Failure) result.message else JSONObject.NULL))
                    }
                }
                WebRouter.json(value = JSONObject().put("results", results))
            }
            request.method == "POST" && request.path == "/api/downloads/remove" -> {
                val body = WebRouter.body(request)
                require(body.getBoolean("confirmed")) { "Confirm removal first" }
                val array = body.getJSONArray("ids")
                require(array.length() in 1..100) { "Select between 1 and 100 downloads" }
                val ids = (0 until array.length()).map { array.getString(it) }.toSet()
                require(ids.size == array.length()) { "Duplicate download selection" }
                val deleteFiles = body.getBoolean("deleteFiles")
                val results = JSONArray()
                for (id in ids) {
                    val record = repository.get(id)
                    if (record == null) { results.put(JSONObject().put("id", id).put("removed", true)); continue }
                    if (record.state == DownloadState.COMPLETED && !deleteFiles) {
                        com.espitman.sdm.ui.CompletedDownloadsVisibility(context).hide(listOf(id))
                        results.put(JSONObject().put("id", id).put("removed", true).put("filesKept", true))
                    } else {
                        val removed = if (record.state == DownloadState.COMPLETED) {
                            val result = com.espitman.sdm.download.CompletedFileDeleteCoordinator.delete(id, repository,
                                com.espitman.sdm.storage.DocumentsContractContentDocuments(context))
                            result is com.espitman.sdm.download.CompletedFileDeleteResult.Deleted || result is com.espitman.sdm.download.CompletedFileDeleteResult.AlreadyMissing
                        } else com.espitman.sdm.download.IncompleteDownloadDeleteCoordinator.delete(id, repository) {
                            DownloadTransferService.cancelTransfer(context, it)
                        }
                        results.put(JSONObject().put("id", id).put("removed", removed).apply {
                            if (!removed) put("error", "Could not remove this item. Check phone folder access and try again.")
                        })
                    }
                }
                WebRouter.json(value = JSONObject().put("results", results))
            }
            request.method in setOf("GET", "HEAD") && request.path.startsWith("/api/files/") -> {
                val match = Regex("/api/files/([A-Za-z0-9-]+)/(main|[0-9]+)/(download|preview)").matchEntire(request.path)
                    ?: return WebRouter.json(404, JSONObject().put("error", "File not found"))
                val (id, index, operation) = match.destructured
                val download = repository.get(id) ?: return WebRouter.json(404, JSONObject().put("error", "File not found"))
                WebFiles(context).stream(download, index, operation == "preview", request.headers["range"], browser)
            }
            request.method == "POST" && request.path == "/api/downloads/review" -> {
                val array = WebRouter.body(request).getJSONArray("links")
                require(array.length() in 1..100) { "Add between 1 and 100 links" }
                val links = (0 until array.length()).map { array.getString(it).trim() }
                WebRouter.json(value = WebHttpReview.inspect(links, AppRepositories.metadataRetriever()))
            }
            request.method == "POST" && request.path == "/api/downloads/add" -> {
                val body = WebRouter.body(request)
                val links = body.getJSONArray("links")
                require(links.length() in 1..100) { "Add between 1 and 100 links" }
                val urls = (0 until links.length()).map { links.getString(it).trim() }
                require(urls.all { it.length in 1..8192 }) { "Invalid link length" }
                val destination = body.optString("folder").takeIf { it.isNotBlank() }?.let { id ->
                    folders.resolve(browser.id, id)
                    folders.allocator(browser.id, id)
                }
                val schedule = if (body.has("schedule")) WebSchedule.decode(body.get("schedule")) else null
                val limit = if (!body.has("speedLimit") || body.isNull("speedLimit")) null else {
                    val value = body.getJSONObject("speedLimit")
                    val unit = value.getString("unit")
                    require(unit in setOf("KB/s", "MB/s")) { "Invalid speed unit" }
                    com.espitman.sdm.domain.IndividualSpeedLimit.parse(value.getString("value"), unit == "MB/s")
                        ?: throw IllegalArgumentException("Enter a positive speed limit")
                }
                val results = JSONArray()
                // Each link has its own result: one failed probe does not discard other links.
                for (url in urls) {
                    when (val result = AppRepositories.submissionCoordinator(context).submit(url, body.optBoolean("startNow", true),
                        deferUntilResumed = !body.optBoolean("startNow", true),
                        destinationAllocatorOverride = destination, schedule = schedule, speedLimitBytesPerSecond = limit)) {
                        is com.espitman.sdm.download.SubmissionResult.Success -> results.put(JSONObject()
                            .put("id", result.download.id).put("name", result.download.fileName).put("accepted", true))
                        is com.espitman.sdm.download.SubmissionResult.Failure -> results.put(JSONObject().put("error", result.message))
                    }
                }
                WebRouter.json(value = JSONObject().put("results", results))
            }
            request.method == "POST" && request.path == "/api/backup/export" -> {
                val body = WebRouter.body(request)
                WebRouter.json(value = backups.export(browser.id, body.getBoolean("downloads"), body.getBoolean("settings")))
            }
            request.method in setOf("GET", "HEAD") && request.path.startsWith("/api/backup/export/") ->
                backups.download(browser.id, request.path.removePrefix("/api/backup/export/"))
            request.method == "POST" && request.path == "/api/backup/review" -> {
                require(request.headers["content-type"]?.substringBefore(';') in setOf("application/json", "application/octet-stream")) { "Choose an SDM JSON backup" }
                WebRouter.json(value = backups.review(browser.id, request.body))
            }
            request.method == "POST" && request.path == "/api/backup/cancel" -> {
                backups.cancel(browser.id, WebRouter.body(request).getString("review"))
                WebRouter.json(value = JSONObject().put("ok", true))
            }
            request.method == "POST" && request.path == "/api/backup/restore" -> {
                val body = WebRouter.body(request)
                WebRouter.json(value = backups.restore(browser.id, body.getString("review"), body.optBoolean("settings", true), body.optBoolean("confirmed", false)))
            }
            request.method == "GET" && request.path.startsWith("/api/download/link/") -> {
                val id = request.path.removePrefix("/api/download/link/")
                val record = repository.get(id) ?: return WebRouter.json(404, JSONObject().put("error", "Download no longer exists"))
                WebRouter.json(value = JSONObject().put("url", record.url))
            }
            request.method == "POST" && request.path == "/api/download/link/bulk/check" -> {
                val body=WebRouter.body(request);val ids=body.getJSONArray("ids");val links=body.getJSONArray("links")
                require(ids.length() in 1..100&&links.length() in 1..100) { "Select up to 100 downloads and links" }
                WebRouter.json(value=bulkLinkRefresh.check(browser.id,(0 until ids.length()).map { ids.getString(it) },(0 until links.length()).map { links.getString(it).trim() }))
            }
            request.method == "POST" && request.path == "/api/download/link/bulk/apply" -> {
                val body=WebRouter.body(request)
                WebRouter.json(value=bulkLinkRefresh.apply(browser.id,body.getString("review"),body.getJSONArray("mappings")))
            }
            request.method == "POST" && request.path == "/api/download/link/bulk/cancel" -> {
                bulkLinkRefresh.cancel(browser.id,WebRouter.body(request).getString("review"))
                WebRouter.json(value=JSONObject().put("ok",true))
            }
            request.method == "POST" && request.path == "/api/download/link/check" -> {
                val body = WebRouter.body(request)
                WebRouter.json(value = linkRefresh.check(browser.id, body.getString("id"), body.getString("url")))
            }
            request.method == "POST" && request.path == "/api/download/link/apply" -> {
                val body = WebRouter.body(request)
                WebRouter.json(value = linkRefresh.apply(browser.id, body.getString("review"), body.optBoolean("restartConfirmed", false)))
            }
            request.method == "POST" && request.path == "/api/download/link/cancel" -> {
                linkRefresh.cancel(browser.id, WebRouter.body(request).getString("review"))
                WebRouter.json(value = JSONObject().put("ok", true))
            }
            request.method == "POST" && request.path == "/api/download/checksum" -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                val supplied = body.optString("expected").trim().takeIf { it.isNotEmpty() }
                val expected = supplied?.let {
                    requireNotNull(com.espitman.sdm.network.ReferenceSha256Parser.parseHexHeader(it)) { "Enter a 64-character SHA-256 checksum" }
                }
                require(checksumGate.tryLock()) { "A checksum verification is already running. Try again when it finishes" }
                try {
                    com.espitman.sdm.storage.FolderMutationGate.mutex.withLock {
                        val record = repository.get(id) ?: return@withLock WebRouter.json(404, JSONObject().put("error", "Download no longer exists"))
                        require(!record.isTorrent) { "Torrent integrity is verified by the torrent engine" }
                        if (record.state == DownloadState.COMPLETED && (expected != null || record.referenceSha256 != null) && !WebFiles(context).canAccess(record, "main")) {
                            return@withLock WebRouter.json(value = JSONObject().put("status", "UNAVAILABLE").put("message", "File unavailable. Check folder access on your phone"))
                        }
                        fun guard(input: java.io.InputStream) = object : java.io.FilterInputStream(input) {
                            private fun approved() { if (WebPanelRuntime.access.browsers.value.none { it.id == browser.id }) throw AccessDenied() }
                            override fun read(): Int { approved(); return super.read() }
                            override fun read(buffer: ByteArray, offset: Int, length: Int): Int { approved(); return super.read(buffer, offset, length) }
                        }
                        var actual: String? = null
                        val verification = if (expected == null) record else record.copy(referenceSha256 = expected)
                        val result = com.espitman.sdm.download.DownloadChecksumVerifier.verify(verification,
                            openStream = { guard(java.io.FileInputStream(it)) },
                            openContentUri = { guard(context.contentResolver.openInputStream(android.net.Uri.parse(it)) ?: throw java.io.FileNotFoundException()) },
                            onDigest = { _, digest -> actual = digest })
                        if (WebPanelRuntime.access.browsers.value.none { it.id == browser.id }) throw AccessDenied()
                        val status = when (result) {
                            com.espitman.sdm.download.ChecksumVerificationResult.Match -> "MATCH"
                            com.espitman.sdm.download.ChecksumVerificationResult.Mismatch -> "MISMATCH"
                            com.espitman.sdm.download.ChecksumVerificationResult.NoReference -> "NO_REFERENCE"
                            com.espitman.sdm.download.ChecksumVerificationResult.NotCompleted -> "NOT_COMPLETED"
                            com.espitman.sdm.download.ChecksumVerificationResult.MissingFile -> "UNAVAILABLE"
                            com.espitman.sdm.download.ChecksumVerificationResult.Failure -> "FAILED"
                        }
                        WebRouter.json(value = JSONObject().put("status", status).put("message", result.message)
                            .put("algorithm", "SHA-256").put("expected", verification.referenceSha256 ?: JSONObject.NULL)
                            .put("actual", actual ?: JSONObject.NULL))
                    }
                } finally { checksumGate.unlock() }
            }
            request.method == "POST" && request.path == "/api/settings/reset" -> {
                require(WebRouter.body(request).optBoolean("confirmed")) { "Confirm resetting settings first" }
                com.espitman.sdm.data.settings.SettingsReset.apply(SettingsRepository.get(context), com.espitman.sdm.storage.CategoryFolderStore.get(context))
                com.espitman.sdm.download.DailyBulkSchedule.arm(context)
                AppRepositories.networkRestriction(context).apply()
                AppRepositories.queueScheduler(context).schedule()
                AppRepositories.scheduleCoordinator(context).apply()
                WebRouter.json(value = JSONObject().put("ok", true))
            }
            request.method == "POST" && request.path == "/api/settings" -> {
                val body = WebRouter.body(request)
                SettingsRepository.get(context).update { WebSettingsPatch.apply(it, body) }
                com.espitman.sdm.download.DailyBulkSchedule.arm(context)
                AppRepositories.networkRestriction(context).apply()
                AppRepositories.queueScheduler(context).schedule()
                AppRepositories.scheduleCoordinator(context).apply()
                WebRouter.json(value = JSONObject().put("ok", true))
            }
            request.method == "POST" && request.path == "/api/download/schedule" -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                val schedule = WebSchedule.decode(body.get("schedule"))
                val updated = repository.updateSchedule(id, schedule, System.currentTimeMillis())
                    ?: return WebRouter.json(404, JSONObject().put("error", "Download no longer exists"))
                AppRepositories.scheduleCoordinator(context).apply()
                WebRouter.json(value = JSONObject().put("id", updated.id).put("schedule", WebSchedule.encode(updated.schedule)))
            }
            request.method == "POST" && request.path == "/api/download/limit" -> {
                val body = WebRouter.body(request)
                val id = body.getString("id")
                if (repository.get(id) == null) return WebRouter.json(404, JSONObject().put("error", "Download no longer exists"))
                val limit = if (body.getBoolean("unlimited")) null else {
                    val unit = body.getString("unit")
                    require(unit in setOf("KB/s", "MB/s")) { "Invalid speed unit" }
                    com.espitman.sdm.domain.IndividualSpeedLimit.parse(body.getString("value"), unit == "MB/s")
                        ?: throw IllegalArgumentException("Enter a positive speed limit")
                }
                val updated = repository.updateSpeedLimit(id, limit, System.currentTimeMillis())
                WebRouter.json(value = JSONObject().put("id", updated.id).put("speedLimit", updated.speedLimitBytesPerSecond ?: JSONObject.NULL))
            }
            request.method == "POST" && request.path == "/api/download/action" -> {
                val body = WebRouter.body(request)
                val ids = body.getJSONArray("ids")
                require(ids.length() in 1..100) { "Select between 1 and 100 downloads" }
                val action = body.getString("action")
                require(action in setOf("pause", "resume", "cancel", "priority", "top", "retry")) { "Unknown download action" }
                val results = JSONArray()
                if(action=="top") {
                    val selected=(0 until ids.length()).map { ids.getString(it) }.toSet()
                    val changed=WebQueueCommands.moveGroupToTop(repository,selected,System.currentTimeMillis())
                    for(id in selected) {
                        val record=repository.get(id)
                        results.put(JSONObject().put("id",id).put("accepted",record!=null&&record.state!=DownloadState.COMPLETED)
                            .apply { if(record==null)put("error","Download no longer exists") else if(record.state==DownloadState.COMPLETED)put("error","Completed downloads cannot be reordered") })
                    }
                    if(changed) AppRepositories.queueScheduler(context).schedule()
                    return WebRouter.json(value=JSONObject().put("results",results).put("changed",changed))
                }
                for (i in 0 until ids.length()) {
                    val id = ids.getString(i)
                    val record = repository.get(id)
                    if (record == null) { results.put(JSONObject().put("id", id).put("error", "Download no longer exists")); continue }
                    if (action in setOf("resume", "retry")) {
                        val error = when {
                            record.state !in setOf(DownloadState.QUEUED, DownloadState.PAUSED, DownloadState.FAILED, DownloadState.CANCELLED) -> "Download is already active or complete"
                            record.schedule?.isOpen(System.currentTimeMillis()) == false -> "Waiting for scheduled time"
                            !AppRepositories.networkRestriction(context).allowsTransfers() -> "Reconnect the phone to an allowed network before resuming"
                            com.espitman.sdm.domain.DownloadFailure.classify(record.error) == com.espitman.sdm.domain.DownloadFailure.BROWSER_SESSION_EXPIRED -> "Sign in using the SDM Browser session before retrying this protected link"
                            else -> null
                        }
                        if (error != null) { results.put(JSONObject().put("id", id).put("accepted", false).put("error", error)); continue }
                    }
                    when (action) {
                        "pause" -> DownloadTransferService.pauseTransfer(context, id)
                        "resume", "retry" -> if (record.state == DownloadState.QUEUED) AppRepositories.queueScheduler(context).startQueued(id) else AppRepositories.queueScheduler(context).resume(id)
                        "cancel" -> DownloadTransferService.cancelTransfer(context, id)
                        "priority" -> repository.togglePriority(id, System.currentTimeMillis())
                    }
                    results.put(JSONObject().put("id", id).put("accepted", true))
                }
                AppRepositories.queueScheduler(context).schedule()
                WebRouter.json(value = JSONObject().put("results", results))
            }
            request.method == "POST" && request.path == "/api/queue/reorder" -> {
                val body = WebRouter.body(request)
                val ids = body.getJSONArray("ids")
                require(ids.length() in 1..100)
                val changed = repository.reorderQueuedGroup((0 until ids.length()).map { ids.getString(it) }.toSet(),
                    body.getString("target"), body.optBoolean("after"), System.currentTimeMillis())
                WebRouter.json(value = JSONObject().put("changed", changed))
            }
            else -> WebRouter.json(404, JSONObject().put("error", "Endpoint not found"))
        }
    }
    @Synchronized private fun snapshot(): JSONObject {
        val now = System.currentTimeMillis()
        val hidden = com.espitman.sdm.ui.CompletedDownloadsVisibility(context).hiddenIds()
        val records = repository.downloads.value.filterNot { it.state == DownloadState.COMPLETED && it.id in hidden }
        val rates = speeds.bytesPerSecondById(records, now)
        val settings = SettingsRepository.get(context).settings.value
        val downloads = JSONArray(records.map { download ->
            val torrent = com.espitman.sdm.torrent.TorrentRuntime.telemetry.value[download.id]
            val savedTorrent = if (download.isTorrent && torrent == null)
                com.espitman.sdm.torrent.TorrentStore(context).progress(download.id)?.takeIf { it.downloaded == download.downloadedBytes } else null
            val uploaded = torrent?.uploaded ?: savedTorrent?.uploaded ?: 0L
            JSONObject().put("id", download.id).put("name", download.fileName).put("state", download.state.name)
                .put("downloaded", download.downloadedBytes).put("total", download.totalBytes ?: JSONObject.NULL)
                .put("speed", torrent?.downloadRate?.toLong() ?: rates[download.id] ?: 0L)
                .put("connections", if (download.state in setOf(DownloadState.CONNECTING, DownloadState.DOWNLOADING)) HttpConnectionTelemetry.active.value[download.id] ?: 0 else 0)
                .put("torrent", download.isTorrent).put("peers", torrent?.peers ?: 0).put("seeds", torrent?.seeds ?: 0)
                .put("uploadRate", torrent?.uploadRate ?: 0).put("seeding", torrent?.seeding ?: false)
                .put("uploaded", uploaded).put("ratio", torrent?.ratio ?: if (download.downloadedBytes > 0) uploaded.toDouble() / download.downloadedBytes else 0.0)
                .put("checking", torrent?.checking ?: false)
                .put("fileProgress", JSONObject((torrent?.fileProgress ?: savedTorrent?.files.orEmpty()).mapKeys { it.key.toString() }))
                .put("peerDetails", JSONArray(torrent?.peerDetails.orEmpty().map { JSONObject().put("address", it.address)
                    .put("client", it.client).put("downloadRate", it.downloadRate).put("uploadRate", it.uploadRate).put("progress", it.progress) }))
                .put("trackers", JSONArray(torrent?.trackers.orEmpty().map { JSONObject().put("url", it.url).put("verified", it.verified) }))
                .put("priority", download.priority).put("order", download.sortOrder).put("createdAt", download.createdAtEpochMillis)
                .put("schedule", WebSchedule.encode(download.schedule)).put("retryAt", settings.automaticRetry().dueAt(download) ?: JSONObject.NULL)
                .put("technical", JSONObject()
                    .put("source", com.espitman.sdm.ui.detailsSourceHost(download.url))
                    .put("savePath", com.espitman.sdm.ui.detailsDestinationDisplay(download))
                    .put("security", com.espitman.sdm.ui.detailsSecurityLabel(download.url))
                    .put("resume", com.espitman.sdm.ui.detailsResumeSupportLabel(download))
                    .put("headersAvailable", false).put("segmentsAvailable", false))
                .put("error", download.error ?: JSONObject.NULL).put("speedLimit", download.speedLimitBytesPerSecond ?: JSONObject.NULL)
                .put("referenceSha256", download.referenceSha256 ?: JSONObject.NULL)
        })
        return JSONObject().put("version", 1).put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0").put("time", now).put("device", android.os.Build.MODEL)
            .put("saveLocation", AppRepositories.saveLocation(context).current().displayLabel)
            .put("zone", java.time.ZoneId.systemDefault().id).put("downloads", downloads).put("settings", JSONObject()
                .put("connections", settings.connections).put("simultaneous", settings.simultaneous)
                .put("wifiOnly", settings.wifiOnly).put("retryCount", settings.retryCount).put("retryDelaySeconds", settings.retryDelaySeconds)
                .put("autoResume", settings.autoResume).put("downloadComplete", settings.downloadComplete).put("speedAlerts", settings.speedAlerts)
                .put("dailyBulkScheduleEnabled", settings.dailyBulkScheduleEnabled).put("dailyResumeMinute", settings.dailyResumeMinute).put("dailyPauseMinute", settings.dailyPauseMinute)
                .put("unlimitedSpeed", settings.unlimitedSpeed).put("speedLimitMbps", settings.speedLimitMbps).put("speedLimitWifiOnly", settings.speedLimitWifiOnly).put("theme", settings.theme))
    }
}
