package com.espitman.sdm.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.storage.*
import com.espitman.sdm.ui.theme.SdmMuted
import com.espitman.sdm.web.WebPanelRuntime
import com.espitman.sdm.web.WebFiles
import kotlinx.coroutines.*

@Composable internal fun WebFileHandoffSheet(onToast:(String)->Unit) {
    val context=LocalContext.current
    val requests=WebPanelRuntime.fileHandoff
    val pending by requests.pending.collectAsState()
    val approvals by WebPanelRuntime.access.requests.collectAsState()
    val folder by WebPanelRuntime.folderAccess.pending.collectAsState()
    val scope=rememberCoroutineScope()
    if(approvals.isNotEmpty()||folder!=null)return
    val request=pending ?: return
    val title=if(request.action==CompletedFileAction.Open)"Open file on phone" else "Share file on phone"
    val dismiss={requests.dismiss(request.id)}
    SettingsSheet(SdmIcons.File,"LOCAL NETWORK",title,"Requested by ${request.browserName}",dismiss,true) {
        Text(request.identity.fileName,fontSize=15.sp,modifier=Modifier.padding(top=20.dp))
        Text("Choose an app on your phone. The web panel does not choose a recipient.",color=SdmMuted,fontSize=12.sp,lineHeight=18.sp,modifier=Modifier.padding(top=10.dp))
        Row(Modifier.fillMaxWidth().padding(top=20.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            ArchiveButton("Decline",false,Modifier.weight(1f),onClick=dismiss)
            ArchiveButton(if(request.action==CompletedFileAction.Open)"Open" else "Share",true,Modifier.weight(1f)) {
                val accepted=requests.consume(request.id) ?: return@ArchiveButton
                scope.launch {
                    val current=withContext(Dispatchers.IO) {
                        val repository=AppRepositories.downloads(context)
                        repository.awaitInitialized()
                        repository.get(accepted.identity.downloadId)?.let { WebFiles(context).identity(it,accepted.index) }
                    }
                    if(!WebPanelRuntime.enabled(context)||System.currentTimeMillis()>=accepted.expires||WebPanelRuntime.access.browsers.value.none { it.id==accepted.owner })onToast("File request expired or browser access was revoked")
                    else if(current!=accepted.identity)onToast("File changed or is no longer available")
                    else CompletedFileShareAccess(context).perform(accepted.action,accepted.identity).message?.let(onToast)
                }
            }
        }
    }
}
