package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.*

@Composable
internal fun SettingsScreen() {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.1.0" }
    Column(Modifier.fillMaxSize().background(SdmBackground)) {
        AppHeader("Settings", showMore = false)
        LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 112.dp)) {
            item { AccountCard() }
            item {
                SettingsGroup("DOWNLOAD BEHAVIOR") {
                    ValueRow(SdmIcons.Connections, "Connections", "Parallel threads per download", "16", chevron = true)
                    SettingDivider()
                    ValueRow(SdmIcons.Simultaneous, "Simultaneous downloads", "Maximum active downloads", "3", chevron = true)
                    SettingDivider()
                    ToggleRow(SdmIcons.Refresh, "Auto-resume", "Continue interrupted downloads", true)
                }
            }
            item { SettingsGroup("NETWORK") { ToggleRow(SdmIcons.Wifi, "Wi-Fi only", "Pause downloads on mobile data", true) } }
            item { SettingsGroup("STORAGE") { ValueRow(SdmIcons.Folder, "Save location", "/Download/SDM", chevron = true) } }
            item {
                SettingsGroup("NOTIFICATIONS") {
                    ToggleRow(SdmIcons.Notifications, "Download complete", "Notify when a transfer finishes", true)
                    SettingDivider()
                    ToggleRow(SdmIcons.Speed, "Speed alerts", "Warn when transfers stall", false)
                }
            }
            item {
                SettingsGroup("APPEARANCE") {
                    ValueRow(SdmIcons.Theme, "Theme", "Black & Gold", themeSwatch = true)
                    SettingDivider()
                    ValueRow(SdmIcons.Language, "Language", "English only", "Fixed")
                }
            }
            item { SettingsGroup("ABOUT") { ValueRow(SdmIcons.Info, "SDM version", "Sibi Download Manager", version) } }
            item {
                Button(
                    onClick = {},
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF191414), contentColor = SdmDanger),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.dp, SdmDanger.copy(alpha = .32f)),
                    modifier = Modifier.padding(top = 22.dp).fillMaxWidth().height(50.dp),
                ) { Text("Reset settings", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}

@Composable
private fun AccountCard() {
    Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmGold.copy(alpha = .3f)), shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(50.dp).background(Color(0xFF191914), RoundedCornerShape(15.dp)).border(1.dp, SdmGold.copy(alpha = .5f), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) {
                Text("SD", color = SdmGoldHigh, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Column(Modifier.weight(1f)) {
                Text("SDM PREMIUM", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.43.sp)
                Text("Sibi Download Manager", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("High-speed downloads with private browsing and smart resume.", color = SdmMuted, fontSize = 11.sp, lineHeight = 16.sp, modifier = Modifier.padding(top = 5.dp))
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(SdmSuccess, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text("All systems ready", color = SdmSuccess, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(top = 22.dp)) {
        Text(title, color = SdmMuted, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.43.sp, modifier = Modifier.padding(bottom = 12.dp))
        Card(colors = CardDefaults.cardColors(containerColor = SdmSurface), border = BorderStroke(1.dp, SdmLine), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(), content = content)
    }
}

@Composable
private fun SettingDivider() = HorizontalDivider(color = SdmLine, thickness = 1.dp)

@Composable
private fun SettingIcon(icon: ImageVector) {
    Box(Modifier.size(36.dp).background(Color(0xFF1D1E1F), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = SdmGoldHigh, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun RowScope.SettingCopy(title: String, subtitle: String) {
    Column(Modifier.weight(1f)) {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(subtitle, color = SdmMuted, fontSize = 11.sp, lineHeight = 15.sp, modifier = Modifier.padding(top = 3.dp))
    }
}

@Composable
private fun ValueRow(icon: ImageVector, title: String, subtitle: String, value: String = "", chevron: Boolean = false, themeSwatch: Boolean = false) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingIcon(icon)
        SettingCopy(title, subtitle)
        if (value.isNotEmpty()) Text(value, color = SdmGoldHigh, fontSize = if (chevron) 14.sp else 11.sp, fontWeight = FontWeight.ExtraBold)
        if (chevron) Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(16.dp))
        if (themeSwatch) Box(Modifier.size(width = 36.dp, height = 28.dp).background(Color(0xFF090909), RoundedCornerShape(9.dp)).border(5.dp, Color(0xFF17181A), RoundedCornerShape(9.dp)).border(1.dp, SdmGold.copy(alpha = .45f), RoundedCornerShape(9.dp)))
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, initial: Boolean) {
    var checked by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).toggleable(checked, role = Role.Switch, onValueChange = { checked = it }).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingIcon(icon)
        SettingCopy(title, subtitle)
        Box(Modifier.size(width = 48.dp, height = 28.dp).background(if (checked) Color(0xFF332E15) else Color(0xFF242528), CircleShape).border(1.dp, if (checked) SdmGold.copy(alpha = .7f) else Color(0xFF474641), CircleShape).padding(4.dp)) {
            Box(Modifier.align(if (checked) Alignment.CenterEnd else Alignment.CenterStart).size(20.dp).background(if (checked) SdmGoldHigh else SdmMuted, CircleShape))
        }
    }
}
