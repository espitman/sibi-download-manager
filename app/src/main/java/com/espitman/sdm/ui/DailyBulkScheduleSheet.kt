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
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.ui.theme.SdmDanger
import com.espitman.sdm.ui.theme.SdmGold
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmSurface
import com.espitman.sdm.ui.theme.SdmText
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
internal fun DailyBulkScheduleSheet(
    settings: SdmSettings,
    visible: Boolean,
    onDismiss: () -> Unit,
    onSave: (Boolean, Int, Int) -> Unit,
) {
    val context = LocalContext.current
    val alarm = remember(context) { context.getSystemService(AlarmManager::class.java) }
    var enabled by remember { mutableStateOf(settings.dailyBulkScheduleEnabled) }
    var resumeMinute by remember { mutableIntStateOf(settings.dailyResumeMinute) }
    var pauseMinute by remember { mutableIntStateOf(settings.dailyPauseMinute) }
    var editing by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf(false) }
    SettingsSheet(
        icon = SdmIcons.Schedule,
        eyebrow = "DOWNLOAD BEHAVIOR",
        title = "Scheduled downloads",
        description = "Resume and pause all downloads at your chosen times every day.",
        onDismiss = onDismiss,
        visible = visible,
    ) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleOption("Off", !enabled, Modifier.weight(1f)) { enabled = false }
            ScheduleOption("Daily", enabled, Modifier.weight(1f)) { enabled = true }
        }
        if (enabled) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && alarm?.canScheduleExactAlarms() == false) {
                Text("Android may run this schedule late until precise alarms are allowed.",
                    color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 12.dp))
                Text("Allow precise timing", color = SdmGoldHigh, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 6.dp).clickable {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                data = Uri.parse("package:${context.packageName}")
                            })
                        }
                    })
            }
            DailyTimeRow("Resume all", resumeMinute, editing == "resume", Modifier.padding(top = 14.dp)) {
                editing = if (editing == "resume") null else "resume"
            }
            if (editing == "resume") TimeChoice(resumeMinute) { resumeMinute = it; error = false }
            DailyTimeRow("Pause all", pauseMinute, editing == "pause", Modifier.padding(top = 9.dp)) {
                editing = if (editing == "pause") null else "pause"
            }
            if (editing == "pause") TimeChoice(pauseMinute) { pauseMinute = it; error = false }
            if (error) Text("Choose two different times", color = SdmDanger, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 17.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ScheduleOption("Cancel", false, Modifier.weight(1f), onDismiss)
            ScheduleOption("Save schedule", true, Modifier.weight(1.35f)) {
                if (resumeMinute == pauseMinute) error = true
                else onSave(enabled, resumeMinute, pauseMinute)
            }
        }
    }
}

@Composable
private fun DailyTimeRow(label: String, minute: Int, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.fillMaxWidth().height(50.dp).background(SdmSurface, RoundedCornerShape(12.dp))
            .border(1.dp, if (selected) SdmGold else SdmLine, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = SdmText, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text("%02d:%02d".format(minute / 60, minute % 60), color = SdmGoldHigh, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
internal fun TimeChoice(minute: Int, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        TimeWheel("Hour", 24, minute / 60, Modifier.weight(1f)) { onChange(it * 60 + minute % 60) }
        TimeWheel("Minute", 60, minute % 60, Modifier.weight(1f)) { onChange(minute / 60 * 60 + it) }
    }
}

@Composable
private fun TimeWheel(label: String, count: Int, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = selected)
    val fling = rememberSnapFlingBehavior(lazyListState = state)
    LaunchedEffect(state, count) {
        snapshotFlow { state.firstVisibleItemIndex.coerceIn(0, count - 1) }
            .distinctUntilChanged()
            .collect { onSelect(it) }
    }
    Column(modifier) {
        Text(label, color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        Box(Modifier.fillMaxWidth().height(120.dp).background(SdmSurface, RoundedCornerShape(12.dp))
            .border(1.dp, SdmLine, RoundedCornerShape(12.dp))) {
            Box(Modifier.fillMaxWidth().height(40.dp).align(Alignment.Center)
                .background(SdmGold.copy(alpha = .15f)))
            LazyColumn(
                state = state,
                flingBehavior = fling,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 40.dp),
                modifier = Modifier.fillMaxWidth().height(120.dp),
            ) {
                items((0 until count).toList()) { value ->
                    Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                        Text("%02d".format(value), color = if (value == selected) SdmGoldHigh else SdmMuted,
                            fontSize = if (value == selected) 19.sp else 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleOption(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.height(44.dp).clickable(onClick = onClick),
        color = if (selected) SdmGold else SdmSurface,
        contentColor = if (selected) Color.Black else SdmText,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, if (selected) SdmGold else SdmLine),
    ) { Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) } }
}
