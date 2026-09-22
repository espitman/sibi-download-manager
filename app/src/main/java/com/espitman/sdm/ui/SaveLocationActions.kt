package com.espitman.sdm.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.storage.OpenDocumentTreeAccess
import com.espitman.sdm.storage.SaveLocationPickerResult
import com.espitman.sdm.storage.SaveLocationStore

internal data class SaveLocationActions(
    val label: String,
    val openPicker: () -> Unit,
)

@Composable
internal fun rememberSaveLocationActions(onToast: (String) -> Unit): SaveLocationActions {
    val context = LocalContext.current
    val store = remember(context) { SaveLocationStore.get(context) }
    val coordinator = remember(context) { AppRepositories.saveLocation(context) }
    val location by store.location.collectAsState()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val uriString = result.data?.data?.toString()
        val flags = result.data?.flags ?: 0
        when (
            val outcome = coordinator.applyPickerResult(
                resultCode = result.resultCode,
                uriString = uriString,
                returnedGrantFlags = flags,
            )
        ) {
            SaveLocationPickerResult.Canceled -> Unit
            is SaveLocationPickerResult.Accepted -> Unit
            is SaveLocationPickerResult.Failed -> onToast(outcome.message)
        }
    }
    LaunchedEffect(coordinator) {
        coordinator.validatePersisted()?.let { onToast(it.message) }
    }
    return SaveLocationActions(
        label = location.displayLabel,
        openPicker = {
            coordinator.validatePersisted()?.let { onToast(it.message) }
            val initial = store.read().treeUri
            launcher.launch(OpenDocumentTreeAccess.createPickerIntent(initial))
        },
    )
}