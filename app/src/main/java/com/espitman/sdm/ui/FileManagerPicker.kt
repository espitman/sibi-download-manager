package com.espitman.sdm.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.ui.theme.SdmGoldHigh
import com.espitman.sdm.ui.theme.SdmLine
import com.espitman.sdm.ui.theme.SdmText
import com.espitman.sdm.ui.theme.sdmColor
import kotlinx.coroutines.delay

@Stable
internal class FileManagerPickerController {
    var discovery by mutableStateOf<FileManagerDiscovery?>(null)
        private set
    var rendered by mutableStateOf<FileManagerDiscovery?>(null)
        internal set

    fun show(discovery: FileManagerDiscovery) {
        this.discovery = discovery
    }

    fun dismiss() {
        discovery = null
    }

    fun present(outcome: FileManagerPickerOutcome, onToast: (String) -> Unit) {
        presentFileManagerPicker(outcome, show = ::show, onToast = onToast)
    }
}

@Composable
internal fun rememberFileManagerPickerController(): FileManagerPickerController {
    val controller = remember { FileManagerPickerController() }
    LaunchedEffect(controller.discovery) {
        if (controller.discovery != null) controller.rendered = controller.discovery
        else {
            delay(SDM_SHEET_TRAVEL_MS.toLong())
            controller.rendered = null
        }
    }
    return controller
}

@Composable
internal fun FileManagerPickerHost(
    controller: FileManagerPickerController,
    onToast: (String) -> Unit,
    startFileManager: FileManagerStarter = SaveLocationFileManagers.DefaultStarter,
) {
    val context = LocalContext.current
    controller.rendered?.let { discovery ->
        FileManagerPicker(
            options = discovery.options,
            onDismiss = { controller.dismiss() },
            visible = controller.discovery != null,
        ) { option ->
            controller.dismiss()
            try {
                startFileManager.start(context, option, discovery.folderIntent)
            } catch (_: Exception) {
                onToast(FILE_MANAGER_UNAVAILABLE_TOAST)
            }
        }
    }
}

@Composable
internal fun FileManagerPicker(
    options: List<FileManagerOption>,
    onDismiss: () -> Unit,
    visible: Boolean,
    onSelect: (FileManagerOption) -> Unit,
) {
    SettingsSheet(SdmIcons.FolderPlain, "STORAGE", "Open save location", "Choose a file manager for the current download folder.", onDismiss, visible) {
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp)
                        .background(sdmColor(0xFF111214, 0xFFFBFAF6), RoundedCornerShape(13.dp))
                        .border(1.dp, SdmLine, RoundedCornerShape(13.dp))
                        .clickable { onSelect(option) }.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(SdmIcons.FolderPlain, null, tint = SdmGoldHigh, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(option.label, color = SdmText, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
