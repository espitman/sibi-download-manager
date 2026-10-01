package com.espitman.sdm.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.espitman.sdm.storage.*
import com.espitman.sdm.ui.theme.*
import kotlinx.coroutines.*

@Composable internal fun AutomaticFoldersSheet(onDismiss:()->Unit,onToast:(String)->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope();val host=rememberSdmSheetHost();val dismiss={host.dismissThen(onDismiss)}
    val store=remember {CategoryFolderStore.get(context)};val settings by store.settings.collectAsState();val unavailable by store.unavailable.collectAsState()
    var editing by remember {mutableStateOf<FileCategory?>(null)};var useDefault by remember {mutableStateOf(true)}
    var draft by remember {mutableStateOf<CategoryFolderRule?>(null)};var error by remember {mutableStateOf<String?>(null)};var busy by remember {mutableStateOf(false)}
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result->
        val category=editing
        val uri=result.data?.data
        if(result.resultCode==android.app.Activity.RESULT_OK && category!=null && uri!=null) scope.launch {
            busy=true;error=null
            try {
                val rule=withContext(Dispatchers.IO) {
                    val outcome=StorageAccessPolicy.interpretPickerResult(result.resultCode,uri.toString(),result.data?.flags ?: 0)
                    require(outcome is OpenDocumentTreeOutcome.Accepted) {"Could not use that folder."}
                    val grants=PersistableTreeUriGrants(context.contentResolver)
                    check(grants.takeReadWrite(outcome.uriString,outcome.takeFlags) is PersistableGrantResult.Success) {"Could not keep access to this folder."}
                    val inspection=DocumentsContractTreeAccess(context.contentResolver).inspect(outcome.uriString)
                    check(inspection.state==UserTreeState.Writable) {"Folder is unavailable or not writable."}
                    CategoryFolderRule(outcome.uriString,SaveLocationLabels.fromTree(outcome.uriString,inspection.displayName))
                }
                if(editing==category) {draft=rule;useDefault=false}
            } catch(c:CancellationException) {throw c} catch(e:Exception) {error=e.message ?: "Could not choose folder."} finally {busy=false}
        }
    }
    fun choose() {if(!busy) picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))}
    LaunchedEffect(settings.rules) {
        withContext(Dispatchers.IO) {
            val grants=PersistableTreeUriGrants(context.contentResolver)
            val trees=DocumentsContractTreeAccess(context.contentResolver)
            settings.rules.forEach { (type,rule) ->
                val available=try {grants.hasReadWrite(rule.treeUri) && trees.inspect(rule.treeUri).state==UserTreeState.Writable} catch(_:Exception) {false}
                if(available) store.markAvailable(type) else store.markUnavailable(type)
            }
        }
    }
    val category=editing
    SettingsSheet(SdmIcons.Folder,"STORAGE",if(category==null) "Automatic folders" else "${category.label} folder",
        if(category==null) "Choose where each file type is saved." else "Used for new ${category.label.lowercase()} downloads.",
        {if(editing!=null) {editing=null;error=null} else dismiss()},host.visible) {
        Column(Modifier.heightIn(max=400.dp).verticalScroll(rememberScrollState())) {
            if(category==null) {
                Box(Modifier.fillMaxWidth().padding(top=14.dp,bottom=10.dp).border(1.dp,SdmLine,RoundedCornerShape(13.dp))) {
                ToggleRow(SdmIcons.Folder,"Enable automatic folders","Apply rules to new downloads",settings.enabled) { enabled->
                    scope.launch {try {withContext(Dispatchers.IO) {store.setEnabled(enabled)}} catch(_:Exception) {error="Could not save folder settings."}}
                }
                }
                Column(Modifier.fillMaxWidth().border(1.dp,SdmLine,RoundedCornerShape(13.dp)).padding(horizontal=10.dp)) {
                FileCategory.entries.forEach {type->
                    val rule=settings.rules[type]
                    Row(Modifier.fillMaxWidth().clickable {editing=type;draft=rule;useDefault=rule==null;error=null}.padding(vertical=10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(34.dp).background(SdmBackground,RoundedCornerShape(10.dp)),contentAlignment=Alignment.Center) {Icon(when(type) { FileCategory.VIDEO->SdmIcons.Video;FileCategory.AUDIO->SdmIcons.Audio;FileCategory.DOCUMENTS->SdmIcons.File;FileCategory.ARCHIVES->SdmIcons.Archive;FileCategory.IMAGES->SdmIcons.Image;FileCategory.OTHER->SdmIcons.FolderPlain },null,tint=SdmGoldHigh,modifier=Modifier.size(19.dp))}
                        Column(Modifier.weight(1f).padding(start=11.dp)) {
                            Text(type.label,fontSize=12.sp,fontWeight=FontWeight.Bold)
                            Text(if(type in unavailable && rule!=null) "${rule.label} · unavailable; using default" else rule?.label ?: "Default save location",color=if(type in unavailable) SdmDanger else SdmMuted,fontSize=10.sp,modifier=Modifier.padding(top=4.dp))
                        }
                        Icon(SdmIcons.Chevron,null,tint=SdmMuted,modifier=Modifier.size(16.dp))
                    }
                    if(type!=FileCategory.OTHER) HorizontalDivider(color=SdmLine)
                }
                }
                ArchiveNote("Unknown file types use Other or the default save location. Unavailable folders fall back to your default.")
            } else {
                FolderRadio("Default save location","Follow the main Save location setting",useDefault) {useDefault=true}
                FolderRadio("Custom folder","Use a separate folder for ${category.label.lowercase()}",!useDefault) {useDefault=false;if(draft==null) choose()}
                if(!useDefault) {
                    Row(Modifier.fillMaxWidth().padding(top=10.dp).border(1.dp,SdmLine,RoundedCornerShape(13.dp)).padding(12.dp),
                        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Icon(SdmIcons.FolderPlain,null,tint=SdmGoldHigh,modifier=Modifier.size(23.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Selected folder",color=SdmMuted,fontSize=10.sp)
                            Text(draft?.label ?: "No folder selected",fontSize=12.sp,fontWeight=FontWeight.Bold,modifier=Modifier.padding(top=4.dp))
                        }
                        ArchiveButton("Change folder",false,Modifier.width(110.dp),!busy) {choose()}
                    }
                }
                ArchiveNote("Existing downloads keep their current destination.")
            }
            if(busy) Text("Checking folder…",color=SdmGoldHigh,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
            if(error!=null) Text(error!!,color=SdmDanger,fontSize=11.sp,modifier=Modifier.padding(top=10.dp))
        }
        if(category==null) ArchiveButton("Done",true,Modifier.fillMaxWidth().padding(top=16.dp),!busy,onClick=dismiss)
        else Row(Modifier.padding(top=16.dp),horizontalArrangement=Arrangement.spacedBy(9.dp)) {
            ArchiveButton("Cancel",false,Modifier.weight(1f),!busy) {editing=null;error=null}
            ArchiveButton("Save rule",true,Modifier.weight(1.15f),!busy && (useDefault || draft!=null)) {
                busy=true;val selected=if(useDefault) null else draft
                scope.launch {
                    try {
                        withContext(Dispatchers.IO) {store.save(category,selected)}
                        onToast("${category.label} folder saved");editing=null
                    } catch(c:CancellationException) {throw c} catch(_:Exception) {error="Could not save rule."} finally {busy=false}
                }
            }
        }
    }
}

@Composable private fun FolderRadio(title:String,subtitle:String,selected:Boolean,onChange:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().clickable(role=androidx.compose.ui.semantics.Role.RadioButton) {onChange(true)}.padding(vertical=14.dp),verticalAlignment=Alignment.Top,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top=2.dp).size(21.dp).border(2.dp,if(selected) SdmGoldHigh else SdmMuted,CircleShape),contentAlignment=Alignment.Center) {
            if(selected) Box(Modifier.size(10.dp).background(SdmGoldHigh,CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title,fontSize=13.sp,fontWeight=FontWeight.Bold)
            Text(subtitle,color=SdmMuted,fontSize=11.sp,lineHeight=16.sp,modifier=Modifier.padding(top=5.dp))
        }
    }
}
