package com.espitman.sdm.web

import android.content.Context
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.*
import com.espitman.sdm.network.*
import com.espitman.sdm.download.DownloadTransferService
import com.espitman.sdm.storage.FolderMutationGate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Browser-owned immutable identities; a client mapping cannot introduce another download. */
internal class WebBulkLinkRefresh(private val context: Context) {
    private data class Draft(val owner:String,val records:List<Download>,val metadata:List<DownloadMetadata>,
        val expires:Long,var applying:Boolean=false,val applied:MutableSet<Int> = mutableSetOf(),val appliedDownloads:MutableSet<String> = mutableSetOf())
    private val drafts=mutableMapOf<String,Draft>()
    private val checking=mutableSetOf<String>()
    @Synchronized fun expire(approved:Set<String>) {
        drafts.entries.removeAll { !it.value.applying&&(it.value.owner !in approved||it.value.expires<=System.currentTimeMillis()) }
    }
    @Synchronized fun cancel(owner:String,token:String) {
        val draft=drafts[token]?:return
        require(draft.owner==owner&&!draft.applying) { "Bulk review is unavailable" }
        drafts.remove(token)
    }
    private fun requireApproved(owner:String) { if(WebPanelRuntime.access.browsers.value.none { it.id==owner })throw AccessDenied() }
    suspend fun check(owner:String,ids:List<String>,links:List<String>):JSONObject {
        require(ids.size in 1..100&&ids.toSet().size==ids.size) { "Select between 1 and 100 downloads" }
        require(links.size in 1..100&&links.all { it.length in 1..8192&&DownloadUrl.validate(it) is DownloadUrlResult.Valid }) { "Enter valid HTTP/HTTPS links, one per line" }
        synchronized(this) {
            expire(WebPanelRuntime.access.browsers.value.map { it.id }.toSet())
            require(checking.size<4&&owner !in checking&&drafts.size+checking.size<8) { "Bulk link checks are busy" }
            checking.add(owner)
        }
        try {
            requireApproved(owner)
            val repo=AppRepositories.downloads(context);repo.awaitInitialized()
            val initial=ids.map { repo.get(it)?:error("Download no longer exists") }
            require(initial.all { !it.isTorrent&&it.state!=DownloadState.COMPLETED }) { "Choose incomplete HTTP downloads" }
            for(record in initial) if(record.state in setOf(DownloadState.QUEUED,DownloadState.CONNECTING,DownloadState.DOWNLOADING)) {
                requireApproved(owner)
                DownloadTransferService.pauseTransfer(context,record.id)
                withTimeout(10_000) { repo.downloads.first { rows -> rows.firstOrNull { it.id==record.id }?.state !in setOf(DownloadState.QUEUED,DownloadState.CONNECTING,DownloadState.DOWNLOADING) } }
            }
            val expected=ids.map { repo.get(it)?:error("Download no longer exists") }
            require(expected.all { it.state !in setOf(DownloadState.QUEUED,DownloadState.CONNECTING,DownloadState.DOWNLOADING,DownloadState.COMPLETED) }) { "A selected download changed. Check again" }
            val metadata=withTimeout(90_000) { links.map { url ->
                requireApproved(owner)
                when(val result=withTimeout(30_000) { AppRepositories.metadataRetriever().retrieve(url) }) {
                is DownloadMetadataResult.Success -> result.metadata
                is DownloadMetadataResult.Failure -> error(ErrorReportSanitizer.sanitize(result.message,160))
            } } }
            val token=UUID.randomUUID().toString()
            synchronized(this) {
                require(WebPanelRuntime.access.browsers.value.any { it.id==owner }) { "Browser access was revoked" }
                drafts[token]=Draft(owner,expected,metadata,System.currentTimeMillis()+120_000)
            }
            return JSONObject().put("review",token)
                .put("downloads",JSONArray(expected.map { JSONObject().put("id",it.id).put("name",it.fileName).put("downloaded",it.downloadedBytes) }))
                .put("candidates",JSONArray(metadata.mapIndexed { index,item ->
                    JSONObject().put("index",index).put("name",item.suggestedFilename).put("size",item.contentLength?:JSONObject.NULL)
                        .put("suggested",expected.singleOrNull { it.fileName.equals(item.suggestedFilename,true) }?.id?:JSONObject.NULL)
                        .put("preserve",JSONObject().apply { expected.forEach { put(it.id,DownloadLinkRefresh.canPreserve(it,item)) } })
                }))
        } finally { synchronized(this) { checking.remove(owner) } }
    }
    suspend fun apply(owner:String,token:String,mappings:JSONArray):JSONObject {
        val draft=synchronized(this) {
            val value=drafts[token]?:error("Bulk review expired. Check again")
            require(value.owner==owner&&value.expires>System.currentTimeMillis()&&!value.applying) { "Bulk review is unavailable" }
            value
        }
        data class Mapping(val index:Int,val record:Download,val restart:Boolean)
        require(mappings.length() in 1..draft.metadata.size) { "Choose at least one matching download" }
        val selected=(0 until mappings.length()).map { position ->
            val item=mappings.getJSONObject(position);val rawIndex=item.get("index")
            require(rawIndex is Number&&rawIndex.toDouble().isFinite()&&rawIndex.toDouble()==rawIndex.toInt().toDouble()) { "Invalid candidate index" }
            val index=rawIndex.toInt()
            require(index in draft.metadata.indices&&index !in draft.applied) { "Candidate is unavailable" }
            val record=draft.records.singleOrNull { it.id==item.getString("id") }?:error("Download is outside this selection")
            val restart=!DownloadLinkRefresh.canPreserve(record,draft.metadata[index])
            require(!restart||item.getBoolean("restartConfirmed")) { "Confirm restart from zero for each changed file" }
            Mapping(index,record,restart)
        }
        require(selected.map { it.index }.toSet().size==selected.size&&selected.map { it.record.id }.toSet().size==selected.size) { "Each link and download must be matched only once" }
        synchronized(this) { require(drafts[token]===draft&&!draft.applying) { "Bulk review is unavailable" };require(selected.none { it.index in draft.applied||it.record.id in draft.appliedDownloads }) { "Candidate was already applied" };draft.applying=true }
        try {
            val repo=AppRepositories.downloads(context);val results=JSONArray()
            for(mapping in selected) {
                try {
                    val updated=FolderMutationGate.mutex.withLock {
                        if(WebPanelRuntime.access.browsers.value.none { it.id==owner })throw AccessDenied()
                        require(draft.expires>System.currentTimeMillis()) { "Bulk review expired. Check again" }
                        repo.refreshLink(mapping.record.id,mapping.record,draft.metadata[mapping.index],mapping.restart,System.currentTimeMillis())
                    }
                    synchronized(this) { draft.applied.add(mapping.index);draft.appliedDownloads.add(mapping.record.id) }
                    val resumed=try {
                        AppRepositories.queueScheduler(context).resume(updated.id)
                        true
                    } catch(cancelled:CancellationException) { throw cancelled }
                    catch(_:Exception) { false }
                    results.put(JSONObject().put("index",mapping.index).put("id",updated.id).put("ok",true)
                        .apply { if(!resumed)put("warning","Link replaced. Resume this download from SDM when ready.") })
                } catch(cancelled:CancellationException) { throw cancelled }
                catch(error:Exception) { results.put(JSONObject().put("index",mapping.index).put("id",mapping.record.id).put("ok",false)
                    .put("error",ErrorReportSanitizer.sanitize(error.message,160))) }
            }
            return JSONObject().put("results",results)
        } finally { synchronized(this) { draft.applying=false } }
    }
}
