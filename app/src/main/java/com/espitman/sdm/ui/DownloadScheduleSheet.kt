package com.espitman.sdm.ui

import android.app.AlarmManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.domain.DownloadSchedule
import com.espitman.sdm.ui.theme.SdmDanger
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

private val onceFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm")
    .withResolverStyle(ResolverStyle.STRICT)
private val dailyFormat = DateTimeFormatter.ofPattern("HH:mm")

internal fun scheduleSummary(schedule: DownloadSchedule?, now: Long = System.currentTimeMillis()): String =
    when (schedule?.kind) {
        null -> "Any time"
        DownloadSchedule.Kind.ONCE -> {
            val start = Instant.ofEpochMilli(schedule.startEpochMillis!!)
                .atZone(ZoneId.systemDefault()).format(onceFormat)
            if (schedule.endEpochMillis != null && now >= schedule.endEpochMillis) "Schedule ended"
            else "Starts $start"
        }
        DownloadSchedule.Kind.DAILY -> {
            val first = LocalTime.ofSecondOfDay(schedule.startMinuteOfDay!!.toLong() * 60)
                .format(dailyFormat)
            val last = LocalTime.ofSecondOfDay(schedule.endMinuteOfDay!!.toLong() * 60)
                .format(dailyFormat)
            "Daily $first–$last" +
                if (schedule.zoneId == ZoneId.systemDefault().id) "" else " (${schedule.zoneId})"
        }
    }

@Composable
internal fun DownloadScheduleSheet(
    initial: DownloadSchedule?,
    visible: Boolean,
    onDismiss: () -> Unit,
    onApply: (DownloadSchedule?) -> Unit,
) {
    val zone = remember(initial) {
        if (initial?.kind == DownloadSchedule.Kind.DAILY) ZoneId.of(initial.zoneId!!)
        else ZoneId.systemDefault()
    }
    val context = LocalContext.current
    val alarm = remember(context) { context.getSystemService(AlarmManager::class.java) }
    val initialStart = remember { LocalDateTime.now(zone).plusHours(1).withSecond(0).withNano(0) }
    var kind by remember(initial) { mutableStateOf(initial?.kind ?: DownloadSchedule.Kind.ONCE) }
    var onceStart by remember(initial) {
        mutableStateOf(initial?.startEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(onceFormat) }
            ?: initialStart.format(onceFormat))
    }
    var onceEnd by remember(initial) {
        mutableStateOf(initial?.endEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone).format(onceFormat) } ?: "")
    }
    var dailyStart by remember(initial) {
        mutableStateOf(initial?.startMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "01:00")
    }
    var dailyEnd by remember(initial) {
        mutableStateOf(initial?.endMinuteOfDay?.let { "%02d:%02d".format(it / 60, it % 60) } ?: "07:00")
    }
    var error by remember { mutableStateOf<String?>(null) }
    var editingOnceTime by remember { mutableStateOf<String?>(null) }

    SettingsSheet(
        icon = SdmIcons.Schedule,
        eyebrow = "DOWNLOAD BEHAVIOR",
        title = "Schedule download",
        description = "Choose a one-time start or a daily time window. Times use ${zone.id}.",
        onDismiss = onDismiss,
        visible = visible,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm?.canScheduleExactAlarms() == false) {
            Text(
                "Android may start a scheduled download late until precise alarms are allowed.",
                color = SdmMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 13.dp),
            )
            Text(
                "Allow precise timing",
                color = SdmGold,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 6.dp).clickable {
                    runCatching {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                            data = Uri.parse("package:${context.packageName}")
                        })
                    }
                },
            )
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(DownloadSchedule.Kind.ONCE to "Once", DownloadSchedule.Kind.DAILY to "Daily").forEach { (option, title) ->
                val selected = kind == option
                Surface(
                    modifier = Modifier.weight(1f).height(42.dp).clickable { kind = option; error = null },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected) SdmGold else SdmSurface,
                    contentColor = if (selected) androidx.compose.ui.graphics.Color.Black else SdmText,
                    border = BorderStroke(1.dp, if (selected) SdmGold else SdmLine),
                ) { androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                } }
            }
        }
        if (kind == DownloadSchedule.Kind.ONCE) {
            ScheduleInput("Start · yyyy-MM-dd HH:mm", onceStart) { onceStart = it; error = null }
            Text("Pick start time", color = SdmGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 7.dp).clickable { editingOnceTime = if (editingOnceTime == "start") null else "start" })
            if (editingOnceTime == "start") {
                val startDate = runCatching { LocalDateTime.parse(onceStart, onceFormat) }.getOrNull()
                if (startDate != null) TimeChoice(startDate.hour * 60 + startDate.minute) {
                    onceStart = startDate.withHour(it / 60).withMinute(it % 60).format(onceFormat); error = null
                }
            }
            ScheduleInput("End · optional", onceEnd) { onceEnd = it; error = null }
            if (onceEnd.isNotBlank()) {
                Text("Pick end time", color = SdmGold, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 7.dp).clickable { editingOnceTime = if (editingOnceTime == "end") null else "end" })
                if (editingOnceTime == "end") {
                    val endDate = runCatching { LocalDateTime.parse(onceEnd, onceFormat) }.getOrNull()
                    if (endDate != null) TimeChoice(endDate.hour * 60 + endDate.minute) {
                        onceEnd = endDate.withHour(it / 60).withMinute(it % 60).format(onceFormat); error = null
                    }
                }
            }
        } else {
            Text("Start", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            TimeChoice(LocalTime.parse(dailyStart, dailyFormat).toSecondOfDay() / 60) {
                dailyStart = "%02d:%02d".format(it / 60, it % 60); error = null
            }
            Text("End", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 14.dp))
            TimeChoice(LocalTime.parse(dailyEnd, dailyFormat).toSecondOfDay() / 60) {
                dailyEnd = "%02d:%02d".format(it / 60, it % 60); error = null
            }
        }
        error?.let { Text(it, color = SdmDanger, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp)) }
        Row(Modifier.fillMaxWidth().padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (initial != null) {
                ScheduleButton("Remove", false, Modifier.weight(1f)) { onApply(null) }
            } else {
                ScheduleButton("Cancel", false, Modifier.weight(1f), onDismiss)
            }
            ScheduleButton("Save schedule", true, Modifier.weight(1.25f)) {
                val parsed = runCatching {
                    when (kind) {
                        DownloadSchedule.Kind.ONCE -> {
                            val startLocal = LocalDateTime.parse(onceStart.trim(), onceFormat)
                            require(startLocal.atZone(zone).toLocalDateTime() == startLocal) {
                                "This local time does not exist in the selected time zone"
                            }
                            val start = startLocal.atZone(zone).toInstant().toEpochMilli()
                            val end = onceEnd.trim().takeIf(String::isNotEmpty)?.let {
                                val endLocal = LocalDateTime.parse(it, onceFormat)
                                require(endLocal.atZone(zone).toLocalDateTime() == endLocal) {
                                    "This local time does not exist in the selected time zone"
                                }
                                endLocal.atZone(zone).toInstant().toEpochMilli()
                            }
                            require(start > System.currentTimeMillis()) { "Choose a future start time" }
                            DownloadSchedule(DownloadSchedule.Kind.ONCE, startEpochMillis = start, endEpochMillis = end)
                        }
                        DownloadSchedule.Kind.DAILY -> {
                            val start = LocalTime.parse(dailyStart.trim(), dailyFormat)
                            val end = LocalTime.parse(dailyEnd.trim(), dailyFormat)
                            DownloadSchedule(
                                DownloadSchedule.Kind.DAILY,
                                startMinuteOfDay = start.hour * 60 + start.minute,
                                endMinuteOfDay = end.hour * 60 + end.minute,
                                zoneId = zone.id,
                            )
                        }
                    }
                }
                parsed.onSuccess(onApply).onFailure { error = it.message ?: "Enter a valid date and time" }
            }
        }
    }
}

@Composable
private fun ScheduleInput(label: String, value: String, onValueChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(label, color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 7.dp))
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(color = SdmText, fontSize = 14.sp),
            cursorBrush = SolidColor(SdmGold),
            modifier = Modifier.fillMaxWidth().background(SdmSurface, RoundedCornerShape(12.dp))
                .border(1.dp, SdmLine, RoundedCornerShape(12.dp)).padding(13.dp),
        )
    }
}

@Composable
private fun ScheduleButton(label: String, primary: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(48.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(13.dp),
        color = if (primary) SdmGold else SdmSurface,
        contentColor = if (primary) androidx.compose.ui.graphics.Color.Black else SdmText,
        border = BorderStroke(1.dp, if (primary) SdmGold else SdmLine),
    ) { androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
    } }
}
