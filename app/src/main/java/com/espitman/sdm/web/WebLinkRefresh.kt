package com.espitman.sdm.web

import android.content.Context
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.DownloadLinkRefresh
import com.espitman.sdm.domain.DownloadState
import com.espitman.sdm.domain.DownloadUrl
import com.espitman.sdm.domain.DownloadUrlResult
import com.espitman.sdm.domain.ErrorReportSanitizer
import com.espitman.sdm.network.DownloadMetadata
import com.espitman.sdm.network.DownloadMetadataResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.UUID

/** The browser receives an expiring review token, never a mutable metadata object. */
internal class WebLinkRefresh(private val context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Draft(val owner: String, val expected: Download, val metadata: DownloadMetadata,
        val expires: Long, var applying: Boolean = false)
    private val checking = mutableMapOf<String, Int>()
    private val drafts = mutableMapOf<String, Draft>()
    private fun requireApproved(owner: String) {
        if (WebPanelRuntime.access.browsers.value.none { it.id == owner }) throw AccessDenied()
    }
    @Synchronized fun expire(approved: Set<String>) {
        val now = clock()
        drafts.entries.removeAll { !it.value.applying && (it.value.owner !in approved || it.value.expires <= now) }
    }
    @Synchronized fun cancel(owner: String, token: String) {
        val draft = drafts[token] ?: return
        require(draft.owner == owner) { "Link review is unavailable" }
        require(!draft.applying) { "Link replacement is already running" }
        drafts.remove(token)
    }
    suspend fun check(owner: String, id: String, rawUrl: String): JSONObject {
        synchronized(this) {
            require(checking.values.sum() < 4 && (checking[owner] ?: 0) < 2) { "Link checks are busy. Try again shortly" }
            checking[owner] = (checking[owner] ?: 0) + 1
        }
        try { return checkDraft(owner, id, rawUrl) }
        finally { synchronized(this) { val remaining = (checking[owner] ?: 1) - 1
            if (remaining == 0) checking.remove(owner) else checking[owner] = remaining } }
    }
    private suspend fun checkDraft(owner: String, id: String, rawUrl: String): JSONObject {
        val url = rawUrl.trim()
        require(url.length in 1..8192 && DownloadUrl.validate(url) is DownloadUrlResult.Valid) { "Enter a valid HTTP/HTTPS link" }
        synchronized(this) {
            expire(WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
            require(drafts.size < 64 && drafts.values.count { it.owner == owner } < 8) { "Too many link reviews. Close a review and try again" }
        }
        val repository = AppRepositories.downloads(context)
        requireApproved(owner)
        val original = repository.get(id) ?: error("Download no longer exists")
        require(!original.isTorrent && original.state != DownloadState.COMPLETED) { "Choose an incomplete HTTP download" }
        if (original.state in setOf(DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING)) {
            requireApproved(owner)
            com.espitman.sdm.download.DownloadTransferService.pauseTransfer(context, id)
            withTimeout(10_000) { repository.downloads.first { rows -> rows.firstOrNull { it.id == id }?.state !in
                setOf(DownloadState.QUEUED, DownloadState.CONNECTING, DownloadState.DOWNLOADING) } }
        }
        val expected = repository.get(id) ?: error("Download no longer exists")
        require(expected.state !in setOf(DownloadState.COMPLETED, DownloadState.CONNECTING, DownloadState.DOWNLOADING)) { "Download changed. Pause it and check the link again" }
        requireApproved(owner)
        val metadata = when (val result = withTimeout(30_000) { AppRepositories.metadataRetriever().retrieve(url,
            com.espitman.sdm.network.BrowserRequestContextRegistry.get(id)) }) {
            is DownloadMetadataResult.Success -> result.metadata
            is DownloadMetadataResult.Failure -> throw IllegalArgumentException(ErrorReportSanitizer.sanitize(result.message).ifBlank { "Unable to check link" })
        }
        require(WebPanelRuntime.access.browsers.value.any { it.id == owner }) { "Browser access was removed" }
        val token = UUID.randomUUID().toString()
        synchronized(this) {
            require(drafts.size < 64 && drafts.values.count { it.owner == owner } < 8) { "Too many link reviews" }
            drafts[token] = Draft(owner, expected, metadata, clock() + 120_000)
        }
        return JSONObject().put("review", token).put("name", expected.fileName)
            .put("size", metadata.contentLength ?: JSONObject.NULL).put("downloaded", expected.downloadedBytes)
            .put("preserve", DownloadLinkRefresh.canPreserve(expected, metadata))
    }
    suspend fun apply(owner: String, token: String, restartConfirmed: Boolean): JSONObject {
        val draft = synchronized(this) {
            val value = drafts[token] ?: throw IllegalArgumentException("Link review expired. Check the link again")
            require(value.owner == owner && value.expires > clock()) { "Link review expired. Check the link again" }
            require(!value.applying) { "Link replacement is already running" }
            require(DownloadLinkRefresh.canPreserve(value.expected, value.metadata) || restartConfirmed) { "Confirm restart from zero before replacing this link" }
            value.applying = true; value
        }
        try {
            val repository = AppRepositories.downloads(context)
            com.espitman.sdm.storage.FolderMutationGate.mutex.lock()
            val updated = try {
                requireApproved(owner)
                require(draft.expires > clock()) { "Link review expired. Check the link again" }
                repository.refreshLink(draft.expected.id, draft.expected, draft.metadata,
                    !DownloadLinkRefresh.canPreserve(draft.expected, draft.metadata), clock())
            } finally { com.espitman.sdm.storage.FolderMutationGate.mutex.unlock() }
            synchronized(this) { drafts.remove(token) }
            AppRepositories.queueScheduler(context).resume(updated.id)
            return JSONObject().put("id", updated.id).put("preserved", updated.downloadedBytes > 0).put("ok", true)
        } finally { synchronized(this) { drafts[token]?.applying = false } }
    }
}
