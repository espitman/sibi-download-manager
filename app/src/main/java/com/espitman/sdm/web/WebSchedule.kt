package com.espitman.sdm.web

import com.espitman.sdm.domain.DownloadSchedule
import org.json.JSONObject

internal object WebSchedule {
    fun encode(schedule: DownloadSchedule?): Any = schedule?.let {
        JSONObject().put("kind", it.kind.name).apply {
            if (it.kind == DownloadSchedule.Kind.ONCE) {
                put("start", it.startEpochMillis); put("end", it.endEpochMillis ?: JSONObject.NULL)
            } else { put("startMinute", it.startMinuteOfDay); put("endMinute", it.endMinuteOfDay); put("zone", it.zoneId) }
        }
    } ?: JSONObject.NULL
    fun decode(value: Any): DownloadSchedule? = try {
        decodeValue(value)
    } catch (error: java.time.DateTimeException) {
        throw IllegalArgumentException("Invalid date, time, or time zone", error)
    }
    private fun decodeValue(value: Any): DownloadSchedule? {
        if (value == JSONObject.NULL) return null
        require(value is JSONObject) { "Invalid schedule" }
        fun number(key: String): Long {
            val number = value.get(key)
            require(number is Number && number.toDouble().isFinite() && number.toDouble() == number.toLong().toDouble()) { "Invalid $key" }
            val result = number.toLong()
            require(result in 0..8_640_000_000_000_000L) { "Invalid $key" }
            return result
        }
        return when (value.getString("kind")) {
            "ONCE" -> {
                if (value.has("startLocal")) {
                    require(value.keys().asSequence().all { it in setOf("kind", "startLocal", "endLocal", "zone") }) { "Invalid schedule fields" }
                    val zone = java.time.ZoneId.of(value.getString("zone"))
                    fun instant(key: String): Long {
                        val text = value.getString(key)
                        require(text.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}"))) { "Invalid date or time" }
                        return java.time.LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()
                    }
                    DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = instant("startLocal"),
                        endEpochMillis = if (!value.has("endLocal") || value.isNull("endLocal")) null else instant("endLocal"))
                } else {
                    require(value.keys().asSequence().all { it in setOf("kind", "start", "end") }) { "Invalid schedule fields" }
                    DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = number("start"),
                        endEpochMillis = if (!value.has("end") || value.isNull("end")) null else number("end"))
                }
            }
            "DAILY" -> {
                require(value.keys().asSequence().all { it in setOf("kind", "startMinute", "endMinute", "zone") }) { "Invalid schedule fields" }
                val start = number("startMinute"); val end = number("endMinute")
                require(start < 1440 && end < 1440) { "Invalid daily time" }
                val zone = value.getString("zone")
                require(zone.length in 1..80) { "Invalid time zone" }
                DownloadSchedule(DownloadSchedule.Kind.DAILY, startMinuteOfDay = start.toInt(), endMinuteOfDay = end.toInt(), zoneId = zone)
            }
            else -> throw IllegalArgumentException("Unknown schedule kind")
        }
    }
}
