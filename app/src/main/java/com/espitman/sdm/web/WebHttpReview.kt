package com.espitman.sdm.web

import com.espitman.sdm.network.DownloadMetadataRetriever
import com.espitman.sdm.network.DownloadMetadataResult
import com.espitman.sdm.domain.ErrorReportSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject

/** Read-only probes: no destination allocation, record insertion or transfer scheduling. */
internal object WebHttpReview {
    suspend fun inspect(links: List<String>, retriever: DownloadMetadataRetriever): JSONObject = coroutineScope {
        require(links.size in 1..100 && links.all { it.length in 1..8192 }) { "Add between 1 and 100 valid-length links" }
        val slots = Semaphore(4)
        val results = links.mapIndexed { index, url -> async {
            slots.withPermit {
                val row = JSONObject().put("line", index + 1)
                try {
                    when (val result = retriever.retrieve(url)) {
                        is DownloadMetadataResult.Success -> row.put("ready", true)
                            .put("name", result.metadata.suggestedFilename)
                            .put("size", result.metadata.contentLength ?: JSONObject.NULL)
                        is DownloadMetadataResult.Failure -> row.put("ready", false)
                            .put("error", ErrorReportSanitizer.sanitize(result.message, 160))
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { row.put("ready", false).put("error",
                    ErrorReportSanitizer.sanitize(failure.message, 160).ifBlank { "Could not inspect this link" }) }
                row
            }
        } }.awaitAll()
        JSONObject().put("results", JSONArray(results))
    }
}
