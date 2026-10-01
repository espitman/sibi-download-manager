package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** One shared bucket per file, across every connection. Live policy changes wake waiting transfers. */
class IndividualSpeedLimiters(downloads: StateFlow<List<Download>>, scope: CoroutineScope) {
    private val buckets = ConcurrentHashMap<String, AggregateSpeedLimiter>()
    private val records = downloads
    init {
        scope.launch {
            downloads.map { values -> values.associate { it.id to it.speedLimitBytesPerSecond } }
                .distinctUntilChanged().collect { limits ->
                val ids = limits.keys
                buckets.entries.forEach { (id, bucket) ->
                    bucket.notifyPolicyChanged()
                    if (id !in ids) buckets.remove(id, bucket)
                }
            }
        }
    }
    fun forDownload(id: String): SpeedLimiter = buckets.computeIfAbsent(id) {
        AggregateSpeedLimiter(effectiveBytesPerSecond = {
            records.value.firstOrNull { it.id == id }?.speedLimitBytesPerSecond
        })
    }
}
