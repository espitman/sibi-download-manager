package com.espitman.sdm.web

import com.espitman.sdm.torrent.TorrentContents
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Metadata-only work. A draft never creates a download or requests payload pieces. */
internal class WebTorrentDrafts(
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : AutoCloseable {
    private data class Draft(val owner: String, val expires: Long, var job: Job? = null,
        var contents: TorrentContents? = null, var error: String? = null, var submitting: Boolean = false)
    private val drafts = mutableMapOf<String, Draft>()

    @Synchronized fun start(owner: String, loader: suspend () -> TorrentContents): String {
        expire()
        check(drafts.size < 8 && drafts.values.count { it.owner == owner } < 4) { "Finish or cancel the previous torrent requests" }
        val id = UUID.randomUUID().toString()
        val draft = Draft(owner, clock() + 600_000)
        drafts[id] = draft
        draft.job = scope.launch {
            try {
                val contents = loader()
                synchronized(this@WebTorrentDrafts) { if (drafts[id] === draft) draft.contents = contents }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { synchronized(this@WebTorrentDrafts) { if (drafts[id] === draft) draft.error = failure.message ?: "Unable to read torrent metadata" } }
            catch (_: UnsatisfiedLinkError) { synchronized(this@WebTorrentDrafts) { if (drafts[id] === draft) draft.error = "Torrent engine is unavailable on this device" } }
        }
        return id
    }
    @Synchronized fun status(owner: String, id: String): JSONObject {
        val draft = find(owner, id)
        return JSONObject().put("id", id).put("status", when { draft.submitting -> "submitting"; draft.contents != null -> "ready"; draft.error != null -> "failed"; else -> "resolving" })
            .put("error", draft.error ?: JSONObject.NULL).apply {
                draft.contents?.let { contents ->
                    put("name", contents.name)
                    put("files", JSONArray(contents.files.map { JSONObject().put("index", it.index).put("path", it.path).put("size", it.size) }))
                }
            }
    }
    @Synchronized fun ready(owner: String, id: String): TorrentContents = find(owner, id).contents
        ?: throw IllegalStateException("Torrent metadata is not ready")
    @Synchronized fun remove(owner: String, id: String) { check(!find(owner, id).submitting) { "Torrent submission is already in progress" }; drafts.remove(id)?.job?.cancel() }
    suspend fun <T> submit(owner: String, id: String, block: suspend (TorrentContents) -> T): T {
        val draft = synchronized(this) {
            find(owner, id).also { check(!it.submitting && it.contents != null) { "Torrent submission is unavailable" }; it.submitting = true }
        }
        try {
            val result = block(checkNotNull(draft.contents))
            synchronized(this) { drafts.remove(id) }
            return result
        } catch (failure: Throwable) {
            synchronized(this) { draft.submitting = false }
            throw failure
        }
    }
    @Synchronized fun expire(approved: Set<String>? = null) {
        drafts.entries.removeAll { (_, draft) ->
            val expired = !draft.submitting && (draft.expires <= clock() || approved != null && draft.owner !in approved)
            if (expired) draft.job?.cancel()
            expired
        }
    }
    private fun find(owner: String, id: String): Draft {
        expire()
        return drafts[id]?.takeIf { it.owner == owner } ?: throw IllegalArgumentException("Torrent request expired or is unavailable")
    }
    @Synchronized override fun close() { scope.cancel(); drafts.clear() }
}
