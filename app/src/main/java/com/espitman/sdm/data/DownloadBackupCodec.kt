package com.espitman.sdm.data

import com.espitman.sdm.domain.*
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.storage.*
import org.json.JSONArray
import org.json.JSONObject

/** Portable whitelist, never serialize local paths, cookies, headers or browser/session data. */
object DownloadBackupCodec {
    const val VERSION = 1
    data class Backup(val downloads: List<Download>, val settings: SdmSettings?, val categoryFolders: CategoryFolderSettings? = null)
    fun encode(downloads: List<Download>?, settings: SdmSettings?, now: Long, categoryFolders: CategoryFolderSettings? = null): String {
        require(downloads == null || downloads.size <= LinkArchive.MAX_LINKS)
        val root = JSONObject().put("format", "sdm-backup").put("version", VERSION).put("createdAt", now)
        downloads?.let { root.put("downloads", JSONArray().apply {
            it.forEach { d -> put(JSONObject().put("url", d.url).put("fileName", d.fileName)
                .put("mimeType", d.mimeType).put("totalBytes", d.totalBytes)
                .put("priority", d.priority).put("sortOrder", d.sortOrder)
                .put("speedLimitBytesPerSecond", d.speedLimitBytesPerSecond).apply {
                    d.schedule?.let { s -> put("schedule", JSONObject().put("kind", s.kind.name)
                        .put("startEpochMillis", s.startEpochMillis).put("endEpochMillis", s.endEpochMillis)
                        .put("startMinuteOfDay", s.startMinuteOfDay).put("endMinuteOfDay", s.endMinuteOfDay).put("zoneId", s.zoneId)) }
                }) }
        }) }
        settings?.let { s -> root.put("settings", JSONObject()
            .put("retryCount",s.retryCount).put("retryDelaySeconds",s.retryDelaySeconds)
            .put("connections",s.connections).put("simultaneous",s.simultaneous).put("autoResume",s.autoResume)
            .put("wifiOnly",s.wifiOnly).put("downloadComplete",s.downloadComplete).put("speedAlerts",s.speedAlerts)
            .put("theme",s.theme).put("unlimitedSpeed",s.unlimitedSpeed).put("speedLimitMbps",s.speedLimitMbps)
            .put("speedLimitWifiOnly",s.speedLimitWifiOnly).put("dailyBulkScheduleEnabled",s.dailyBulkScheduleEnabled)
            .put("dailyResumeMinute",s.dailyResumeMinute).put("dailyPauseMinute",s.dailyPauseMinute)) }
        if(settings != null && categoryFolders != null) root.put("categoryFolders", JSONObject()
            .put("enabled", categoryFolders.enabled).put("rules", JSONArray().apply {
                categoryFolders.rules.forEach { (type,rule) -> put(JSONObject().put("type",type.name).put("treeUri",rule.treeUri).put("label",rule.label)) }
            }))
        return root.toString(2).also { require(it.toByteArray().size <= LinkArchive.MAX_FILE_BYTES) { "Backup exceeds 5 MB." } }
    }
    fun decode(text: String): Backup {
        require(text.toByteArray().size <= LinkArchive.MAX_FILE_BYTES) { "Backup exceeds 5 MB." }
        checkDepth(text)
        val tokens = org.json.JSONTokener(text.removePrefix("\uFEFF"))
        val root = tokens.nextValue() as? JSONObject ?: error("Not an SDM backup.")
        require(tokens.nextClean() == '\u0000') { "Unexpected data after backup." }
        require(root.getString("format") == "sdm-backup") { "Not an SDM backup." }
        require(root.strictLong("version") == VERSION.toLong()) { "Unsupported backup version." }
        require(root.has("downloads") || root.has("settings")) { "Backup contains no app data." }
        val array = if (root.has("downloads")) root.getJSONArray("downloads") else JSONArray()
        require(array.length() <= LinkArchive.MAX_LINKS) { "Too many downloads in backup." }
        val downloads = (0 until array.length()).map { index ->
            val d = array.getJSONObject(index)
            val valid = DownloadUrl.validate(d.getString("url")) as? DownloadUrlResult.Valid
                ?: error("Invalid URL at download ${index + 1}.")
            val name = d.getString("fileName")
            require(name.isNotBlank() && name.length <= 255 && !name.contains('/') && !name.contains('\\') && name !in setOf(".","..") && name.none { it.code < 32 }) { "Invalid file name at download ${index+1}." }
            val schedule = if (d.has("schedule")) d.getJSONObject("schedule").let { s -> DownloadSchedule(
                kind = DownloadSchedule.Kind.valueOf(s.getString("kind")), startEpochMillis = s.nullLong("startEpochMillis"),
                endEpochMillis = s.nullLong("endEpochMillis"), startMinuteOfDay = s.nullInt("startMinuteOfDay"),
                endMinuteOfDay = s.nullInt("endMinuteOfDay"), zoneId = if(s.has("zoneId")) s.getString("zoneId") else null) } else null
            Download(url=valid.url, fileName=name, mimeType=if(d.has("mimeType")) d.getString("mimeType") else null,
                totalBytes=d.nullLong("totalBytes"), priority=d.strictLong("priority").also { require(it in 0..Int.MAX_VALUE.toLong()) }.toInt(), sortOrder=d.strictLong("sortOrder").also { require(it>=0) },
                speedLimitBytesPerSecond=d.nullLong("speedLimitBytesPerSecond"), schedule=schedule,
                state=DownloadState.PAUSED, createdAtEpochMillis=0)
        }
        val settings = if (root.has("settings")) root.getJSONObject("settings").let { s ->
            SdmSettings(retryCount=if(s.has("retryCount")) s.strictInt("retryCount") else 2,
                retryDelaySeconds=AutomaticRetrySettings.normalizeDelay(if(s.has("retryDelaySeconds")) s.strictInt("retryDelaySeconds").also { require(it in 1..300) } else 2),
                connections=s.strictInt("connections"), simultaneous=s.strictInt("simultaneous"),
                autoResume=s.getBoolean("autoResume"),wifiOnly=s.getBoolean("wifiOnly"),downloadComplete=s.getBoolean("downloadComplete"),
                speedAlerts=s.getBoolean("speedAlerts"),theme=s.getString("theme"),unlimitedSpeed=s.getBoolean("unlimitedSpeed"),
                speedLimitMbps=s.getDouble("speedLimitMbps").toFloat(),speedLimitWifiOnly=s.getBoolean("speedLimitWifiOnly"),
                dailyBulkScheduleEnabled=s.getBoolean("dailyBulkScheduleEnabled"),dailyResumeMinute=s.strictInt("dailyResumeMinute"),dailyPauseMinute=s.strictInt("dailyPauseMinute")).also {
                require(it.retryCount in 0..10 && it.retryDelaySeconds in AutomaticRetrySettings.DELAYS) { "Invalid retry settings." }
                require(it.connections in listOf(8,16,24,32) && it.simultaneous in 1..10 && it.theme in listOf("dark","light")) { "Invalid settings in backup." }
                require(it.speedLimitMbps.isFinite() && it.speedLimitMbps in 1f..30f && it.dailyResumeMinute in 0..1439 && it.dailyPauseMinute in 0..1439 && it.dailyResumeMinute != it.dailyPauseMinute) { "Invalid speed or schedule settings." }
            }
        } else null
        val categoryFolders = if(settings != null && root.has("categoryFolders")) root.getJSONObject("categoryFolders").let { config ->
            val rules=config.getJSONArray("rules")
            require(rules.length()<=FileCategory.entries.size)
            val mapped=mutableMapOf<FileCategory,CategoryFolderRule>()
            for(i in 0 until rules.length()) {
                val rule=rules.getJSONObject(i);val category=FileCategory.valueOf(rule.getString("type"))
                require(category !in mapped) { "Duplicate folder rule." }
                val uri=rule.getString("treeUri");require(StorageAccessPolicy.validateTreeUri(uri) is TreeUriValidation.Valid) {"Invalid folder rule URI."}
                val label=rule.getString("label");require(label.isNotBlank() && label.length<=255)
                mapped[category]=CategoryFolderRule(uri,label)
            }
            CategoryFolderSettings(config.getBoolean("enabled"),mapped)
        } else null
        return Backup(downloads, settings, categoryFolders)
    }
    private fun checkDepth(text: String) {
        var depth=0;var quoted=false;var escaped=false
        text.forEach { char ->
            if(quoted) { if(escaped) escaped=false else if(char=='\\') escaped=true else if(char=='"') quoted=false }
            else when(char) { '"' -> quoted=true; '{','[' -> {depth++;require(depth<=16) {"Backup nesting is too deep."}}; '}',']' -> depth-- }
        }
    }
    private fun JSONObject.strictInt(key: String): Int = strictLong(key).also {require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()
    private fun JSONObject.strictLong(key: String): Long {
        val number = get(key)
        require(number is Number) { "Invalid numeric field: $key" }
        return java.math.BigDecimal(number.toString()).longValueExact()
    }
    private fun JSONObject.nullLong(key: String): Long? = if (!has(key) || isNull(key)) null else strictLong(key)
    private fun JSONObject.nullInt(key: String): Int? = if (!has(key) || isNull(key)) null else strictLong(key).also {require(it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())}.toInt()
}
