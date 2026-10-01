package com.espitman.sdm.download

import com.espitman.sdm.domain.Download
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Retains all verified range files during folder migration instead of discarding non-contiguous bytes. */
internal object FolderSegmentCheckpoint {
    fun marker(part: File) = File(part.path + ".folder-segments")
    fun file(part: File, range: TransferByteRange) = File(part.path + ".segment-${range.start}-${range.endInclusive}")
    fun save(part: File, ranges: List<TransferByteRange>, download: Download) {
        val json = JSONObject().put("total", download.totalBytes).put("etag", download.etag ?: "")
            .put("modified", download.lastModified ?: "").put("ranges", JSONArray(ranges.map {
                JSONArray(listOf(it.start, it.endInclusive))
            }))
        java.io.FileOutputStream(marker(part)).use { out ->
            out.write(json.toString().toByteArray(Charsets.UTF_8)); out.fd.sync()
        }
    }
    fun load(part: File, download: Download): List<TransferByteRange>? = try {
        val json=JSONObject(marker(part).readText())
        if (json.getLong("total") != download.totalBytes || json.getString("etag") != (download.etag ?: "") ||
            json.getString("modified") != (download.lastModified ?: "")) null
        else {
            val array=json.getJSONArray("ranges")
            val ranges=(0 until array.length()).map { val r=array.getJSONArray(it);TransferByteRange(r.getLong(0),r.getLong(1)) }
            if (ranges.size !in 2..32 || ranges.first().start != 0L || ranges.last().endInclusive != download.totalBytes!!-1 ||
                ranges.zipWithNext().any { (a,b)->a.endInclusive+1 != b.start } ||
                ranges.any { file(part,it).length()>it.length }) null else ranges
        }
    } catch (_: Exception) { null }
    fun bytes(part: File, ranges: List<TransferByteRange>): Long = ranges.sumOf { file(part,it).length().coerceIn(0L,it.length) }
}
