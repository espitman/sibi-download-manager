package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.domain.Download
import com.espitman.sdm.domain.IndividualSpeedLimit
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal fun individualSpeedLimitLabel(bytes: Long?): String = bytes?.let {
    val unit = if (it >= 1_000_000 && it % 1_000_000 == 0L) 1_000_000L else 1_000L
    "${java.math.BigDecimal.valueOf(it).divide(java.math.BigDecimal.valueOf(unit)).stripTrailingZeros().toPlainString()} ${if (unit == 1_000_000L) "MB/s" else "KB/s"}"
} ?: "Unlimited"

@Composable
internal fun IndividualSpeedLimitSheet(download: Download, currentSpeed: String, onDismiss: () -> Unit, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val host = rememberSdmSheetHost()
    val dismiss = { host.dismissThen(onDismiss) }
    var unlimited by remember { mutableStateOf(download.speedLimitBytesPerSecond == null) }
    var megabytes by remember { mutableStateOf(download.speedLimitBytesPerSecond?.let { it >= 1_000_000 && it % 1_000_000 == 0L } ?: false) }
    var value by remember { mutableStateOf(download.speedLimitBytesPerSecond?.let {
        java.math.BigDecimal.valueOf(it).divide(java.math.BigDecimal.valueOf(if (megabytes) 1_000_000 else 1_000)).stripTrailingZeros().toPlainString()
    } ?: "500") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    SettingsSheet(SdmIcons.Gauge, "DOWNLOAD SETTINGS", "Speed limit", "Limit the speed of this download.", dismiss, host.visible) {
        Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
            Text(download.fileName, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 18.dp))
            Text("Current speed: $currentSpeed", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            Row(Modifier.padding(top = 18.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LimitChoice("Unlimited", unlimited, Modifier.weight(1f)) { unlimited = true; error = null }
                LimitChoice("Custom", !unlimited, Modifier.weight(1f)) { unlimited = false }
            }
            Text("Maximum speed", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(value, { value = it; unlimited = false; error = null }, singleLine = true,
                    textStyle = TextStyle(color = SdmText, fontSize = 18.sp), cursorBrush = SolidColor(SdmGold),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).height(48.dp).background(SdmBackground, RoundedCornerShape(12.dp)).border(1.dp, SdmLine, RoundedCornerShape(12.dp)),
                    decorationBox = { inner -> Box(Modifier.padding(horizontal = 14.dp), contentAlignment = Alignment.CenterStart) { inner() } })
                LimitChoice("KB/s", !megabytes, Modifier.width(62.dp)) { megabytes = false; unlimited = false }
                LimitChoice("MB/s", megabytes, Modifier.width(62.dp)) { megabytes = true; unlimited = false }
            }
            if (error != null) Text(error!!, color = SdmDanger, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Column(Modifier.padding(top = 16.dp).fillMaxWidth().border(1.dp, SdmGold.copy(alpha = .2f), RoundedCornerShape(12.dp)).padding(12.dp)) {
                Text("Shared across all connections of this download.", color = SdmText, fontSize = 11.sp)
                Text("The global speed limit still applies.", color = SdmMuted, fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
            }
        }
        Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
            LimitChoice("Cancel", false, Modifier.weight(.35f), enabled = !busy, onClick = dismiss)
            LimitChoice(if (busy) "Applying…" else "Apply limit", true, Modifier.weight(.65f), enabled = !busy) {
                val limit = if (unlimited) null else IndividualSpeedLimit.parse(value, megabytes)
                if (!unlimited && limit == null) { error = "Enter a valid speed greater than zero." }
                else {
                    busy = true
                    scope.launch {
                        try {
                            AppRepositories.downloads(context).updateSpeedLimit(download.id, limit, System.currentTimeMillis())
                            onToast(if (limit == null) "Speed limit removed" else "Speed limit saved")
                            dismiss()
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { error = "Could not save speed limit. Try again." }
                        finally { busy = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun LimitChoice(label: String, selected: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Surface(color = if (selected) SdmGold else SdmBackground,
        contentColor = if (selected) Color(0xFF090909) else SdmText,
        shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (selected) SdmGold else SdmLine),
        modifier = modifier.height(44.dp).clickable(enabled = enabled, onClick = onClick)) {
        Box(contentAlignment = Alignment.Center) { Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
    }
}
