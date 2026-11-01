package com.espitman.sdm.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.*
import com.espitman.sdm.web.WebPanelRuntime

@Composable internal fun WebPanelSheet(onDismiss: () -> Unit, onToast: (String) -> Unit) {
    val context = LocalContext.current
    val status by WebPanelRuntime.status.collectAsState()
    val browsers by WebPanelRuntime.access.browsers.collectAsState()
    var enabled by remember { mutableStateOf(WebPanelRuntime.enabled(context)) }
    var port by remember { mutableStateOf(WebPanelRuntime.port(context).toString()) }
    var code by remember { mutableStateOf(WebPanelRuntime.access.code()) }
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) {
        while (true) {
            WebPanelRuntime.access.expire()
            enabled = WebPanelRuntime.enabled(context)
            code = WebPanelRuntime.access.code()
            kotlinx.coroutines.delay(1_000)
        }
    }
    SettingsSheet(SdmIcons.Wifi, "LOCAL NETWORK", "Web panel", "Control SDM from a browser. Downloads stay on your phone.", onDismiss, true) {
        Column(Modifier.padding(top = 16.dp).heightIn(max = 440.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleRow(SdmIcons.Wifi, "Enable web panel", "Allow approved browsers on your network", enabled) { next ->
                if (next) {
                    val value = port.toIntOrNull()
                    if (value == null || value !in 1024..65535) { onToast("Choose a port from 1024 to 65535"); return@ToggleRow }
                    runCatching { WebPanelRuntime.start(context, value); enabled = true }
                        .onFailure { onToast("Could not start the web panel") }
                } else { WebPanelRuntime.stop(context); enabled = false; code = null }
            }
            Text("LAN address", color = SdmMuted, fontSize = 11.sp)
            Text(status.address ?: status.error ?: "Enable the panel to get its address", color = if (status.address != null) SdmGoldHigh else SdmMuted, fontSize = 13.sp)
            if (status.address != null) WebPanelButton("Copy address") { clipboard.setText(AnnotatedString(status.address!!)); onToast("Address copied") }
            Text("Port", color = SdmMuted, fontSize = 11.sp)
            Surface(color = SdmSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, SdmLine)) {
                BasicTextField(port, { text -> if (text.length <= 5 && text.all(Char::isDigit)) port = text },
                    modifier = Modifier.fillMaxWidth().padding(13.dp), singleLine = true,
                    textStyle = TextStyle(color = SdmText, fontSize = 14.sp))
            }
            if (port != WebPanelRuntime.port(context).toString() ) WebPanelButton("Apply port", true) {
                val value = port.toIntOrNull()
                if (value == null || value !in 1024..65535) onToast("Choose a port from 1024 to 65535")
                else runCatching { WebPanelRuntime.setPort(context, value); onToast("Port updated") }.onFailure { onToast("Could not change the port") }
            }
            if (enabled) {
                Text("PAIRING", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("Approve a request on this phone or share a one-time code.", color = SdmMuted, fontSize = 11.sp)
                code?.let { Text(it.value.chunked(3).joinToString(" "), color = SdmText, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
                WebPanelButton(if (code == null) "Generate pairing code" else "Regenerate pairing code") { code = WebPanelRuntime.access.regenerateCode() }
                Text("Codes expire after two minutes and can be used once.", color = SdmMuted, fontSize = 10.sp)
            }
            if (browsers.isNotEmpty()) Text("APPROVED BROWSERS", color = SdmGoldHigh, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            browsers.forEach { browser ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text(browser.browser, fontSize = 12.sp); Text(browser.address, color = SdmMuted, fontSize = 10.sp) }
                    WebPanelButton("Revoke", danger = true) { runCatching { WebPanelRuntime.access.revoke(browser.id) }.onFailure { onToast("Could not revoke browser access") } }
                }
            }
            Text("Stopping the panel does not stop downloads.", color = SdmMuted, fontSize = 10.sp)
        }
    }
}

@Composable internal fun WebAccessApproval() {
    val requests by WebPanelRuntime.access.requests.collectAsState()
    val request = requests.firstOrNull() ?: return
    SettingsSheet(SdmIcons.Wifi, "LOCAL NETWORK", "Web panel access", "A browser on your local network wants to control SDM.",
        { WebPanelRuntime.access.deny(request.id) }, true) {
        Text(request.browser, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 20.dp))
        Text(request.address, color = SdmMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.weight(1f)) { WebPanelButton("Decline", danger = true) { WebPanelRuntime.access.deny(request.id) } }
            Box(Modifier.weight(1f)) { WebPanelButton("Allow", primary = true) { WebPanelRuntime.access.approve(request.id) } }
        }
        Text("Only approve browsers you trust. You can revoke access in Settings.", color = SdmMuted, fontSize = 10.sp, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable private fun WebPanelButton(label: String, primary: Boolean = false, danger: Boolean = false, onClick: () -> Unit) {
    Surface(color = if (primary) SdmGold else SdmSurface, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, if (primary) SdmGold else SdmLine),
        modifier = Modifier.heightIn(min = 44.dp).clickable(remember { MutableInteractionSource() }, null, onClick = onClick)) {
        Box(Modifier.padding(horizontal = 15.dp, vertical = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, color = if (primary) androidx.compose.ui.graphics.Color(0xFF090909) else if (danger) androidx.compose.ui.graphics.Color(0xFFD98181) else SdmText,
                fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}
