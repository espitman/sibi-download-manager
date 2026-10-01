package com.espitman.sdm.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.settings.SdmSettings
import com.espitman.sdm.ui.theme.*

@Composable internal fun AutomaticRetrySheet(settings: SdmSettings, visible: Boolean, onDismiss: () -> Unit, onSave: (Int, Int) -> Unit) {
    var count by remember { mutableIntStateOf(settings.retryCount) }
    var seconds by remember { mutableIntStateOf(settings.retryDelaySeconds) }
    SettingsSheet(SdmIcons.Refresh, "DOWNLOAD BEHAVIOR", "Automatic retry", "Try again after temporary connection or server errors.", onDismiss, visible) {
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeWheel("Retries", 11, count, Modifier.weight(1f)) { count = it }
            TimeWheel("Seconds", 300, seconds - 1, Modifier.weight(1f), valueOffset = 1) { seconds = it + 1 }
        }
        Text("Set retries to 0 to turn this off. Manual pauses and permanent errors are never retried.", color = SdmMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RetryButton("Cancel", false, true, Modifier.weight(1f), onDismiss)
            RetryButton("Save", true, true, Modifier.weight(1.15f)) { onSave(count, seconds) }
        }
    }
}

@Composable private fun RetryButton(label: String, primary: Boolean, enabled: Boolean, modifier: Modifier, click: () -> Unit) {
    Box(modifier.height(44.dp).background(if(primary) SdmGold.copy(alpha=if(enabled) 1f else .4f) else sdmColor(0xFF080808,0xFFF8F5ED),RoundedCornerShape(13.dp))
        .border(1.dp,if(primary) SdmGold else sdmColor(0xFF2B2A26,0xFFDED7C5),RoundedCornerShape(13.dp)).clickable(enabled=enabled,onClick=click), contentAlignment=androidx.compose.ui.Alignment.Center) {
        Text(label,color=if(primary) androidx.compose.ui.graphics.Color.Black else SdmText,fontSize=12.sp,fontWeight=FontWeight.Bold)
    }
}
