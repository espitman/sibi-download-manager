package com.espitman.sdm.data

import com.espitman.sdm.domain.*
import com.espitman.sdm.download.*
import com.espitman.sdm.network.*
import com.espitman.sdm.storage.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Imports use the same allocation policy as ordinary submissions; existing records always win. */
class LinkArchiveCoordinator(
    private val repository: DownloadRepository,
    private val allocator: DestinationAllocator,
    private val metadata: DownloadMetadataRetriever,
    private val scheduler: DownloadQueueScheduler,
) {
    private val mutex = Mutex()
    data class ImportResult(val added: Int, val skipped: Int, val failed: List<String>)
    suspend fun importLinks(urls: List<String>, startNow: Boolean, onProgress: (Int,Int)->Unit): ImportResult = mutex.withLock {
        require(urls.size <= LinkArchive.MAX_LINKS)
        var added=0; var skipped=0; val failed=mutableListOf<String>()
        urls.forEachIndexed { index,url ->
            if(repository.schedulingSnapshot().any { it.url==url }) skipped++ else {
                var reservation: AllocatedDownloadDestination?=null
                try {
                    require(DownloadUrl.validate(url) is DownloadUrlResult.Valid)
                    val info = when(val result=metadata.retrieve(url)) {
                        is DownloadMetadataResult.Success -> result.metadata
                        is DownloadMetadataResult.Failure -> error(result.message)
                    }
                    withContext(NonCancellable) { reservation=withContext(Dispatchers.IO) { allocator.allocate(info.suggestedFilename,info.contentType) } }
                    currentCoroutineContext().ensureActive()
                    val target=reservation!!; val now=System.currentTimeMillis()
                    val download=Download(url=info.url,fileName=target.fileName,mimeType=info.contentType,
                        destinationPath=target.destinationPath,destinationTreeUri=target.destinationTreeUri,destinationDisplayLabel=target.destinationDisplayLabel,
                        totalBytes=info.contentLength,etag=info.etag,lastModified=info.lastModified,acceptsRanges=info.acceptsRanges,
                        referenceSha256=info.referenceSha256,createdAtEpochMillis=now,sortOrder=now,
                        state=if(startNow) DownloadState.QUEUED else DownloadState.PAUSED)
                    if(withContext(NonCancellable) {repository.insertUniqueBatch(listOf(download))}.isNotEmpty()) { added++;reservation=null }
                    else skipped++
                } catch(c:CancellationException) { throw c }
                catch(_:Exception) { failed+=url }
                finally { reservation?.partFile?.delete() }
            }
            currentCoroutineContext().ensureActive()
            onProgress(index+1,urls.size)
        }
        if(startNow && added>0) scheduler.schedule()
        ImportResult(added,skipped,failed)
    }
    suspend fun restore(downloads: List<Download>): Int = mutex.withLock {
        val existing=repository.schedulingSnapshot().map { it.url }.toMutableSet()
        val candidates=downloads.filter { existing.add(it.url) }
        val reserved=mutableListOf<AllocatedDownloadDestination>(); var inserted=setOf<String>()
        val prepared=mutableListOf<Download>()
        try {
            val now=System.currentTimeMillis()
            val lastOrder=repository.schedulingSnapshot().maxOfOrNull { it.sortOrder } ?: 0
            candidates.sortedBy { it.sortOrder }.forEachIndexed { i,d ->
                val target=withContext(NonCancellable) { withContext(Dispatchers.IO) { allocator.allocate(d.fileName,d.mimeType) }.also {reserved+=it} }
                currentCoroutineContext().ensureActive()
                prepared+=d.copy(id=java.util.UUID.randomUUID().toString(),fileName=target.fileName,
                    destinationPath=target.destinationPath,destinationTreeUri=target.destinationTreeUri,destinationDisplayLabel=target.destinationDisplayLabel,
                    downloadedBytes=0,state=DownloadState.PAUSED,createdAtEpochMillis=now,updatedAtEpochMillis=now,
                    sortOrder=maxOf(lastOrder.coerceAtMost(Long.MAX_VALUE-LinkArchive.MAX_LINKS-1),now)+i+1,error=null,startedAtEpochMillis=null,completedAtEpochMillis=null)
            }
            // No suspension between commit return and tracking the committed IDs.
            inserted=withContext(NonCancellable) {repository.insertUniqueBatch(prepared)}.toSet()
            inserted.size
        } finally {
            reserved.forEachIndexed { i,target -> if(prepared.getOrNull(i)?.id !in inserted) target.partFile.delete() }
        }
    }
}
