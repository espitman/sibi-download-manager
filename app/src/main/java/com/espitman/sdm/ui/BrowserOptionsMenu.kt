package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor

@Composable
internal fun BrowserOptionsMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    desktopSite: Boolean,
    onNewTab: () -> Unit,
    onPrivateTab: () -> Unit,
    onDownloads: () -> Unit,
    onHistory: () -> Unit,
    onFind: () -> Unit,
    onDesktopSite: () -> Unit,
    onSettings: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        containerColor = sdmColor(0xFF1F2124, 0xFFFFFFFF),
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, SdmLine),
    ) {
        Column(Modifier.width(274.dp).padding(10.dp)) {
            Row(
                Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Browser", color = SdmText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Private session", color = SdmMuted, fontSize = 9.sp)
                }
                BrowserMenuIcon(SdmIcons.Lock, green = true)
            }
            HorizontalDivider(color = SdmLine)
            BrowserMenuRow("New tab", null, SdmIcons.Add, onNewTab, compact = true)
            BrowserMenuRow("Private tab", null, SdmIcons.Lock, onPrivateTab, compact = true)
            HorizontalDivider(color = SdmLine)
            BrowserMenuRow("Downloads", "Open transfer list", SdmIcons.Download, onDownloads)
            BrowserMenuRow("History", "Recently visited pages", SdmIcons.Refresh, onHistory)
            BrowserMenuRow("Find in page", "Search visible content", SdmIcons.Search, onFind)
            Row(
                Modifier.fillMaxWidth().height(55.dp).clickable(onClick = onDesktopSite).padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowserMenuIcon(SdmIcons.Browser)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("Desktop site", color = SdmText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text("Request desktop layout", color = SdmMuted, fontSize = 8.sp)
                }
                Switch(checked = desktopSite, onCheckedChange = { onDesktopSite() }, modifier = Modifier.size(width = 38.dp, height = 24.dp))
            }
            HorizontalDivider(color = SdmLine)
            BrowserMenuRow("Browser settings", null, SdmIcons.Settings, onSettings)
        }
    }
}

@Composable
private fun BrowserMenuRow(
    title: String,
    subtitle: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    compact: Boolean = false,
) {
    Row(
        Modifier.fillMaxWidth().height(if (compact) 46.dp else 55.dp)
            .clickable(onClick = onClick).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrowserMenuIcon(icon)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            Text(title, color = SdmText, fontSize = if (compact) 10.sp else 11.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = SdmMuted, fontSize = 8.sp)
        }
        if (!compact) Icon(SdmIcons.Chevron, null, tint = SdmMuted, modifier = Modifier.size(15.dp))
    }
}

@Composable
private fun BrowserMenuIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, green: Boolean = false) {
    Row(
        Modifier.size(32.dp).background(if (green) Color(0xFF20261F) else sdmColor(0xFF25261F, 0xFFF2EAD2), RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = if (green) Color(0xFF86C7A4) else SdmGoldHigh, modifier = Modifier.size(18.dp))
    }
}
