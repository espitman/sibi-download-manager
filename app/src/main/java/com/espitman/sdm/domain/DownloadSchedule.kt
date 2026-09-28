package com.espitman.sdm.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A fixed instant window or a daily wall-clock window in the selected time zone. */
data class DownloadSchedule(
    val kind: Kind,
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val startMinuteOfDay: Int? = null,
    val endMinuteOfDay: Int? = null,
    val zoneId: String? = null,
) {
    enum class Kind { ONCE, DAILY }

    init {
        when (kind) {
            Kind.ONCE -> {
                require(startEpochMillis != null && startEpochMillis >= 0)
                require(endEpochMillis == null || endEpochMillis > startEpochMillis)
                require(startMinuteOfDay == null && endMinuteOfDay == null && zoneId == null)
            }
            Kind.DAILY -> {
                require(startEpochMillis == null && endEpochMillis == null)
                require(startMinuteOfDay in 0..1439 && endMinuteOfDay in 0..1439)
                require(startMinuteOfDay != endMinuteOfDay)
                require(!zoneId.isNullOrBlank())
                ZoneId.of(zoneId)
            }
        }
    }

    fun isOpen(nowEpochMillis: Long): Boolean = when (kind) {
        Kind.ONCE -> nowEpochMillis >= startEpochMillis!! &&
            (endEpochMillis == null || nowEpochMillis < endEpochMillis)
        Kind.DAILY -> windowsAround(nowEpochMillis).any { (start, end) ->
            nowEpochMillis >= start && nowEpochMillis < end
        }
    }

    fun nextBoundary(nowEpochMillis: Long): Long? = when (kind) {
        Kind.ONCE -> listOfNotNull(startEpochMillis, endEpochMillis).firstOrNull { it > nowEpochMillis }
        Kind.DAILY -> windowsAround(nowEpochMillis)
            .flatMap { (start, end) -> listOf(start, end) }
            .filter { it > nowEpochMillis }
            .minOrNull()
    }

    private fun windowsAround(nowEpochMillis: Long): List<Pair<Long, Long>> {
        val zone = ZoneId.of(zoneId!!)
        val date = Instant.ofEpochMilli(nowEpochMillis).atZone(zone).toLocalDate()
        return (-1L..2L).map { offset -> window(date.plusDays(offset), zone) }
    }

    private fun window(date: LocalDate, zone: ZoneId): Pair<Long, Long> {
        val startDateTime = date.atStartOfDay().plusMinutes(startMinuteOfDay!!.toLong())
        val endDateTime = date.atStartOfDay().plusMinutes(endMinuteOfDay!!.toLong())
            .plusDays(if (endMinuteOfDay <= startMinuteOfDay) 1 else 0)
        return startDateTime.atZone(zone).toInstant().toEpochMilli() to
            endDateTime.atZone(zone).toInstant().toEpochMilli()
    }
}
