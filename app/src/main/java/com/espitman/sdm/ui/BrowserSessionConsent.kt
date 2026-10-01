package com.espitman.sdm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.*

@Composable
internal fun BrowserSessionConsent(use: Boolean, retain: Boolean, private: Boolean, enabled: Boolean,
    onUse: (Boolean) -> Unit, onRetain: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SessionChoice("Use this browser session", use, enabled) { onUse(!use) }
        if (use && !private) SessionChoice("Keep session for pause and resume", retain, enabled) { onRetain(!retain) }
        Text(if (private) "Private session stays in memory. Sign in again after restarting the app."
            else if (retain && use) "Session is encrypted on this device and excluded from exports and backups."
            else "Session stays in memory. Sign in again after restarting the app.", color = SdmMuted, fontSize = 11.sp)
    }
}

@Composable
private fun SessionChoice(label: String, checked: Boolean, enabled: Boolean, action: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = action), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        Box(Modifier.size(20.dp).background(if (checked) SdmGold else SdmSurface, RoundedCornerShape(5.dp))
            .border(1.dp, if (checked) SdmGold else SdmLine, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
            if (checked) Icon(SdmIcons.Check, null, tint = SdmBackground, modifier = Modifier.size(14.dp))
        }
        Text(label, color = SdmText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}
