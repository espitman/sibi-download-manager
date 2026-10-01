package com.espitman.sdm.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.espitman.sdm.notification.rememberTransferNotificationPermissionPreparer
import com.espitman.sdm.data.*
import com.espitman.sdm.data.settings.SettingsRepository
import com.espitman.sdm.domain.*
import com.espitman.sdm.storage.*
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.*
import java.io.File
import java.time.LocalDate

private fun archiveCoordinator(context: android.content.Context) = LinkArchiveCoordinator(
    AppRepositories.downloads(context), AppRepositories.destinationAllocator(context),
    AppRepositories.metadataRetriever(), AppRepositories.queueScheduler(context))

private suspend fun readArchive(context: android.content.Context, uri: Uri): Pair<String,String> = withContext(Dispatchers.IO) {
    val name=context.contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {
        if(it.moveToFirst()) it.getString(0) else "Selected file"
    } ?: "Selected file"
    val bytes=context.contentResolver.openInputStream(uri)?.use { readBoundedArchiveBytes(it) }
        ?: error("Could not read the selected file.")
    require(bytes.size<=LinkArchive.MAX_FILE_BYTES) { "File exceeds 5 MB." }
    val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
    name to decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
}

private fun readBoundedArchiveBytes(input: java.io.InputStream): ByteArray {
    val output=java.io.ByteArrayOutputStream(); val buffer=ByteArray(8192)
    while(true) { val read=input.read(buffer); if(read<0) break
        require(output.size()+read<=LinkArchive.MAX_FILE_BYTES) { "File exceeds 5 MB." }
        output.write(buffer,0,read)
    }
    return output.toByteArray()
}

@Composable
internal fun ArchiveButton(text:String,primary:Boolean,modifier:Modifier=Modifier,enabled:Boolean=true,onClick:()->Unit) {
    Surface(color=if(primary) SdmGold else SdmBackground,contentColor=if(primary) Color(0xFF090909) else SdmText,
        shape=RoundedCornerShape(13.dp),border=BorderStroke(1.dp,if(primary) SdmGold else SdmLine),
        modifier=modifier.height(44.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick)) {
        Box(contentAlignment=Alignment.Center) { Text(text,fontSize=12.sp,fontWeight=FontWeight.Bold,maxLines=1) }
    }
}
@Composable internal fun ArchiveNote(text:String) {
    Text(text,color=SdmMuted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=14.dp).fillMaxWidth()
        .border(1.dp,SdmGold.copy(alpha=.25f),RoundedCornerShape(12.dp)).padding(12.dp))
}
@Composable private fun ArchiveField(value:String,onChange:(String)->Unit) {
    BasicTextField(value,onChange,singleLine=true,textStyle=TextStyle(color=SdmText,fontSize=13.sp),cursorBrush=SolidColor(SdmGold),
        modifier=Modifier.fillMaxWidth().height(44.dp).background(SdmBackground,RoundedCornerShape(12.dp)).border(1.dp,SdmLine,RoundedCornerShape(12.dp)),
        decorationBox={ inner->Box(Modifier.padding(horizontal=12.dp),contentAlignment=Alignment.CenterStart) { inner() } })
}
@Composable private fun ArchiveFileRow(name:String,subtitle:String?=null,onClick:(()->Unit)?=null) {
    val shape=RoundedCornerShape(13.dp)
    Row(Modifier.fillMaxWidth().then(if(onClick!=null) Modifier.padding(top=16.dp,bottom=12.dp)
        .background(SdmBackground,shape).border(1.dp,SdmLine,shape).clickable(role=Role.Button,onClick=onClick) else Modifier)
        .padding(horizontal=if(onClick!=null) 10.dp else 0.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).background(SdmBackground, RoundedCornerShape(11.dp))
            .border(1.dp,SdmLine.copy(alpha=.6f),RoundedCornerShape(11.dp)),contentAlignment=Alignment.Center) {
            Icon(SdmIcons.File,null,tint=SdmText,modifier=Modifier.size(21.dp))
        }
        Column(Modifier.weight(1f).padding(start=12.dp)) {
            Text(name,fontSize=13.sp,fontWeight=FontWeight.Bold,maxLines=1,overflow=TextOverflow.Ellipsis)
            if(subtitle!=null) Text(subtitle,color=SdmMuted,fontSize=11.sp,maxLines=1,overflow=TextOverflow.Ellipsis,modifier=Modifier.padding(top=5.dp))
        }
        if(onClick!=null) Icon(SdmIcons.Chevron,null,tint=SdmText,modifier=Modifier.size(18.dp))
    }
}
@Composable private fun ArchiveFileList(names:List<String>) {
    Column(Modifier.fillMaxWidth().padding(top=10.dp).border(1.dp,SdmLine,RoundedCornerShape(13.dp))) {
        names.forEachIndexed { index,name->
            if(index>0) HorizontalDivider(color=SdmLine,thickness=1.dp)
            Box(Modifier.padding(horizontal=12.dp)) {ArchiveFileRow(name)}
        }
    }
}
@Composable internal fun ArchiveSegments(labels:List<String>,selected:Int,enabled:List<Boolean> = labels.map {true},onSelect:(Int)->Unit) {
    Row(Modifier.fillMaxWidth().height(40.dp).background(SdmBackground,RoundedCornerShape(12.dp))
        .border(1.dp,SdmLine,RoundedCornerShape(12.dp))) {
        labels.forEachIndexed {index,label->
            Box(Modifier.weight(1f).fillMaxHeight().background(if(index==selected) SdmGold else Color.Transparent,RoundedCornerShape(12.dp))
                .clickable(enabled=enabled[index],role=Role.Tab) {onSelect(index)},contentAlignment=Alignment.Center) {
                Text(label,color=if(index==selected) Color(0xFF090909) else if(enabled[index]) SdmText else SdmMuted,
                    fontSize=13.sp,fontWeight=FontWeight.Bold)
            }
        }
    }
}
@Composable internal fun ArchiveInfo(text:String) {
    Row(Modifier.fillMaxWidth().border(1.dp,SdmGold.copy(alpha=.45f),RoundedCornerShape(12.dp)).padding(11.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        Icon(SdmIcons.Info,null,tint=SdmGoldHigh,modifier=Modifier.size(21.dp))
        Text(text,color=SdmText,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.weight(1f))
    }
}
@Composable private fun ExportSummary(count:Int) {
    Row(Modifier.fillMaxWidth().padding(top=14.dp).border(1.dp,SdmGold.copy(alpha=.5f),RoundedCornerShape(13.dp))
        .padding(14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Icon(SdmIcons.File,null,tint=SdmGoldHigh,modifier=Modifier.size(26.dp))
        Column(Modifier.weight(1f)) {
            Text("$count links · Plain text (.txt)",fontSize=13.sp,fontWeight=FontWeight.Bold)
            Text("One URL per line. Request headers and cookies are excluded.",color=SdmMuted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=6.dp))
        }
    }
}

@Composable internal fun ImportLinksSheet(onDismiss:()->Unit,onToast:(String)->Unit) {
    val context=LocalContext.current; val scope=rememberCoroutineScope();val host=rememberSdmSheetHost()
    val dismiss={host.dismissThen(onDismiss)}; val repository=AppRepositories.downloads(context)
    var preview by remember { mutableStateOf<LinkArchive.Preview?>(null) };var filename by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf(false) };var progress by remember { mutableStateOf("") }
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri-> if(uri!=null) scope.launch {
        busy=true;error=null;preview=null
        try { val (name,text)=readArchive(context,uri);filename=name
            val existing=repository.schedulingSnapshot().map { it.url }.toSet()
            preview=withContext(Dispatchers.Default) { LinkArchive.preview(text,existing) }
        } catch(c:CancellationException) {throw c} catch(e:Exception) {error=e.message ?: "Could not read file."} finally {busy=false}
    } }
    var pendingStart by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permission=rememberTransferNotificationPermissionPreparer { pendingStart?.invoke(); pendingStart=null }
    fun submit(start:Boolean) {
        val rows=preview?.urls ?: return
        if(busy || rows.isEmpty()) return
        busy=true;error=null
        val action={ scope.launch {
            try {
                val result=archiveCoordinator(context).importLinks(rows,start) { done,total->progress="$done/$total" }
                onToast("${result.added} added · ${result.skipped} skipped · ${result.failed.size} failed")
                if(result.failed.isEmpty()) dismiss() else {
                    preview=LinkArchive.Preview(result.failed,0,0)
                    error="${result.failed.size} links failed. Retry the remaining links."
                }
            } catch(c:CancellationException) {throw c} catch(e:Exception) {error=e.message ?: "Import failed."} finally {busy=false;progress=""}
        }; Unit }
        if(start) { pendingStart=action; permission.prepareForForegroundTransfer {pendingStart=null;action()} } else action()
    }
    SettingsSheet(SdmIcons.File,"LINKS & BACKUP","Import links","Add downloads from a text file.",dismiss,host.visible) {
        Column(Modifier.heightIn(max=390.dp).verticalScroll(rememberScrollState())) {
            ArchiveFileRow("Choose .txt file",onClick={if(!busy) picker.launch(arrayOf("text/plain","application/octet-stream"))})
            if(filename.isNotBlank()) { Text(filename,fontSize=13.sp,fontWeight=FontWeight.Bold);Text("${(preview?.let { it.urls.size+it.duplicates+it.invalid } ?: 0)} links found",color=SdmMuted,fontSize=11.sp) }
            preview?.let { p->
                Row(Modifier.padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
                    for((label,color) in listOf("${p.urls.size} new" to SdmGoldHigh,"${p.duplicates} duplicate" to SdmMuted,"${p.invalid} invalid" to SdmDanger)) {
                        Text(label,color=if(color==SdmGoldHigh) Color(0xFF090909) else color,fontSize=11.sp,fontWeight=FontWeight.Bold,textAlign=androidx.compose.ui.text.style.TextAlign.Center,modifier=Modifier.weight(1f).background(if(color==SdmGoldHigh) SdmGold else Color.Transparent,RoundedCornerShape(10.dp)).border(1.dp,color.copy(alpha=.3f),RoundedCornerShape(10.dp)).padding(vertical=10.dp))
                    }
                }
                p.urls.take(3).forEachIndexed { index,url->
                    if(index>0) HorizontalDivider(color=SdmLine,thickness=1.dp)
                    ArchiveFileRow(Uri.parse(url).lastPathSegment ?: "Download",url)
                }
                if(p.urls.size>3) Text("+ ${p.urls.size-3} more valid links",fontSize=11.sp,color=SdmMuted)
                ArchiveNote("Duplicates and invalid lines will be skipped.")
            }
            if(error!=null) Text(error!!,color=SdmDanger,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
            if(busy) Text(if(progress.isBlank()) "Reading file…" else "Adding $progress",color=SdmGoldHigh,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
        }
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            ArchiveButton("Add to queue",false,Modifier.weight(1f),!busy && preview?.urls?.isNotEmpty()==true) {submit(false)}
            ArchiveButton("Download now",true,Modifier.weight(1f),!busy && preview?.urls?.isNotEmpty()==true) {submit(true)}
        }
    }
}

@Composable internal fun ExportLinksSheet(downloads:List<Download>,selected:Set<String>,onDismiss:()->Unit,onToast:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val host=rememberSdmSheetHost();val dismiss={host.dismissThen(onDismiss)}
    var useSelected by remember { mutableStateOf(selected.isNotEmpty()) };var name by remember { mutableStateOf("sdm-links.txt") }
    var pending by remember { mutableStateOf("") };var error by remember { mutableStateOf<String?>(null) };var busy by remember { mutableStateOf(false) }
    val rows=if(useSelected) downloads.filter {it.id in selected} else downloads
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri->if(uri!=null) scope.launch {
        busy=true
        try {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(pending.toByteArray())} ?: error("Could not write file.")};onToast("Links exported");dismiss()}
        catch(c:CancellationException) {throw c} catch(_:Exception) {error="Could not save links."} finally {busy=false}
    } }
    SettingsSheet(SdmIcons.Link,"LINKS & BACKUP","Export links","Save or share your download URLs.",dismiss,host.visible) {
        Column(Modifier.heightIn(max=390.dp).verticalScroll(rememberScrollState())) {
            Text("Include",fontSize=11.sp,color=SdmMuted,modifier=Modifier.padding(top=16.dp,bottom=8.dp))
            ArchiveSegments(listOf("Selected (${selected.size})","All (${downloads.size})"),if(useSelected) 0 else 1,listOf(selected.isNotEmpty(),true)) {useSelected=it==0}
            ArchiveFileList(rows.take(3).map {it.fileName})
            if(rows.size>3) Text("+ ${rows.size-3} more",fontSize=11.sp,color=SdmMuted)
            ExportSummary(rows.size)
            Text("File name",color=SdmMuted,fontSize=11.sp,modifier=Modifier.padding(top=16.dp,bottom=8.dp));ArchiveField(name) {name=it}
            if(error!=null) Text(error!!,color=SdmDanger,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        }
        Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            ArchiveButton("Share",false,Modifier.weight(1f),rows.isNotEmpty() && !busy) {
                busy=true;scope.launch {
                    try {
                        val file=withContext(Dispatchers.IO) {File(context.cacheDir,"link-exports").apply {mkdirs()}.let {File(it,"sdm-links-${System.currentTimeMillis()}.txt").apply {writeText(LinkArchive.export(rows))}}}
                        val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",file)
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM,uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply {clipData=android.content.ClipData.newRawUri("SDM links",uri)},"Share links"))
                    } catch(c:CancellationException) {throw c} catch(_:Exception) {error="Could not share links."} finally {busy=false}
                }
            }
            ArchiveButton("Save file",true,Modifier.weight(1f),rows.isNotEmpty() && !busy) {pending=LinkArchive.export(rows);save.launch(safeArchiveName(name,"sdm-links.txt","txt"))}
        }
    }
}
internal fun safeArchiveName(raw:String,fallback:String,extension:String):String {
    val safe=raw.trim().replace(Regex("[\\\\/\\p{Cntrl}]"),"_").take(120).ifBlank {fallback}
    return if(safe.endsWith(".$extension",true)) safe else "$safe.$extension"
}

@Composable internal fun BackupRestoreSheet(onDismiss:()->Unit,onToast:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val host=rememberSdmSheetHost();val dismiss={host.dismissThen(onDismiss)}
    val repository=AppRepositories.downloads(context);val settings=SettingsRepository.get(context)
    var restore by remember {mutableStateOf(false)};var includeDownloads by remember {mutableStateOf(true)};var includeSettings by remember {mutableStateOf(true)}
    var name by remember {mutableStateOf("sdm-backup-${LocalDate.now()}.json")};var pending by remember {mutableStateOf("")};var backup by remember {mutableStateOf<DownloadBackupCodec.Backup?>(null)}
    var source by remember {mutableStateOf("")};var duplicates by remember {mutableIntStateOf(0)};var busy by remember {mutableStateOf(false)};var error by remember {mutableStateOf<String?>(null)}
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) {uri->if(uri!=null) scope.launch {
        busy=true
        try {withContext(Dispatchers.IO) {context.contentResolver.openOutputStream(uri,"wt")?.use {it.write(pending.toByteArray())} ?: error("Could not write backup.")};onToast("Backup saved");dismiss()}
        catch(c:CancellationException) {throw c} catch(_:Exception) {error="Could not save backup."} finally {busy=false}
    } }
    val open=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri->if(uri!=null) scope.launch {
        busy=true;backup=null;error=null
        try {val (filename,text)=readArchive(context,uri);val decoded=withContext(Dispatchers.Default) {DownloadBackupCodec.decode(text)}
            source=filename;backup=decoded
            val seen=repository.schedulingSnapshot().map {it.url}.toMutableSet();duplicates=decoded.downloads.count {!seen.add(it.url)}
        } catch(c:CancellationException) {throw c} catch(_:Exception) {error="Invalid, unsupported or unreadable SDM backup."} finally {busy=false}
    } }
    SettingsSheet(SdmIcons.Archive,"APP DATA","Backup & restore","Keep your download list and settings safe.",dismiss,host.visible) {
        Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())) {
            Box(Modifier.padding(top=16.dp,bottom=12.dp)) {
                ArchiveSegments(listOf("Backup","Restore"),if(restore) 1 else 0,listOf(!busy,!busy)) {restore=it==1;error=null}
            }
            if(!restore) {
                Column(Modifier.fillMaxWidth().border(1.dp,SdmLine,RoundedCornerShape(13.dp)).padding(horizontal=12.dp)) {
                    ArchiveCheck("Download list","Links, queue order, schedules and limits",includeDownloads) {includeDownloads=it}
                    HorizontalDivider(color=SdmLine)
                    ArchiveCheck("App settings","Network, speed, appearance and preferences",includeSettings) {includeSettings=it}
                    Box(Modifier.padding(top=2.dp,bottom=10.dp)) {ArchiveInfo("Downloaded files are not included.")}
                }
                Text("File name",color=SdmMuted,fontSize=11.sp,modifier=Modifier.padding(top=16.dp,bottom=8.dp));ArchiveField(name) {name=it}
            } else {
                ArchiveFileRow("Choose backup file",onClick={if(!busy) open.launch(arrayOf("application/json","text/plain","application/octet-stream"))})
                backup?.let { b->
                    Text("Restore preview",fontWeight=FontWeight.Bold,fontSize=13.sp,modifier=Modifier.padding(top=12.dp))
                    Text(source,color=SdmMuted,fontSize=11.sp,modifier=Modifier.padding(top=5.dp))
                    ArchiveNote("${b.downloads.size} downloads · ${if(b.settings!=null) "App settings" else "No settings"} · $duplicates duplicates\nKeep existing downloads; skip duplicates.")
                    ArchiveNote("Restored downloads start paused at 0%. Files and folder permissions are not restored. Missing folder access uses your current default save location.")
                    if(b.settings!=null) ArchiveCheck("Restore app settings","Replace current preferences",includeSettings) {includeSettings=it}
                }
            }
            if(error!=null) Text(error!!,color=SdmDanger,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
            if(busy) Text("Working…",color=SdmGoldHigh,fontSize=11.sp,modifier=Modifier.padding(top=8.dp))
        }
        ArchiveButton(if(restore) "Review & restore" else "Save backup",true,Modifier.fillMaxWidth().padding(top=16.dp),!busy && if(restore) backup!=null else includeDownloads || includeSettings) {
            busy=true;error=null;scope.launch {
                var restoredCount:Int?=null
                try {
                    if(!restore) {
                        val records=if(includeDownloads) repository.schedulingSnapshot() else null
                        pending=withContext(Dispatchers.Default) {DownloadBackupCodec.encode(records,if(includeSettings) settings.settings.value else null,System.currentTimeMillis(),if(includeSettings) CategoryFolderStore.get(context).settings.value else null)}
                        save.launch(safeArchiveName(name,"sdm-backup.json","json"))
                    } else {
                        val b=backup!!;val added=archiveCoordinator(context).restore(b.downloads);restoredCount=added
                        if(includeSettings && b.settings!=null) {
                            settings.update {b.settings}
                            b.categoryFolders?.let { folders-> withContext(Dispatchers.IO) {
                                val store=CategoryFolderStore.get(context);store.replace(folders)
                                val grants=PersistableTreeUriGrants(context.contentResolver)
                                folders.rules.forEach { (category,rule)->if(!grants.hasReadWrite(rule.treeUri)) store.markUnavailable(category) }
                            } }
                            com.espitman.sdm.download.DailyBulkSchedule.arm(context)
                        }
                        onToast("$added downloads restored · ${b.downloads.size-added} skipped");dismiss()
                    }
                } catch(c:CancellationException) {throw c} catch(_:Exception) {error=if(restoredCount!=null) "$restoredCount downloads restored; some settings could not be restored." else if(restore) "Restore failed. Existing downloads were kept." else "Could not create backup."} finally {busy=false}
            }
        }
    }
}
@Composable internal fun ArchiveCheck(title:String,subtitle:String,checked:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked,role=Role.Checkbox,onValueChange=onChange).padding(vertical=13.dp),verticalAlignment=Alignment.CenterVertically) {
        Box(Modifier.size(23.dp).background(if(checked) SdmGold else SdmBackground,RoundedCornerShape(6.dp)).border(1.dp,if(checked) SdmGold else SdmLine,RoundedCornerShape(6.dp)),contentAlignment=Alignment.Center) {
            if(checked) Icon(SdmIcons.Check,null,tint=Color.Black,modifier=Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f).padding(start=12.dp)) {Text(title,fontSize=12.sp,fontWeight=FontWeight.Bold);Text(subtitle,color=SdmMuted,fontSize=10.sp,modifier=Modifier.padding(top=4.dp))}
    }
}
