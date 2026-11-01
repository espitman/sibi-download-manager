package com.espitman.sdm.ui.tutorial

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.espitman.sdm.domain.*
import com.espitman.sdm.ui.*
import com.espitman.sdm.ui.theme.*

/** Deliberately in-memory. Samples have no repository, service, network client or file handle. */
@Stable internal class TourSamples {
    var text by mutableStateOf("https://example.com/sample.zip")
    var selected by mutableStateOf("All")
    var toggle by mutableStateOf(false)
    var count by mutableIntStateOf(2)
    var delaySeconds by mutableIntStateOf(2)
    var reversed by mutableStateOf(false)
    var confirm by mutableStateOf(false)
    var drag by mutableFloatStateOf(0f)
    var minute by mutableIntStateOf(60)
    var endMinute by mutableIntStateOf(420)
    var editTime by mutableStateOf(false)
}
private fun sampleDownload(index:Int=0)=Download(id="tutorial-$index",url="https://example.com/sample.zip",fileName=if(index==0)"Sample archive.zip" else "Sample video.mp4",destinationPath="/Download/SDM",state=if(index==0)DownloadState.DOWNLOADING else DownloadState.PAUSED,totalBytes=1000000000,downloadedBytes=420000000,createdAtEpochMillis=1)
@Composable internal fun SampleDownloadCards() {
    repeat(2) { index -> DownloadCard(mapDownloadToCard(sampleDownload(index),1,740000),onOpen={},onAction={});Spacer(Modifier.height(12.dp)) }
}

@Composable internal fun TutorialScene(step:TourStep,compact:Boolean,s:TourSamples,onBounds:(Rect)->Unit,onFeedback:(String)->Unit,onTheme:(Boolean)->Unit) {
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        onFeedback(if(uri==null)"Folder selection cancelled. Your settings are unchanged." else "Folder selected for this tour only.")
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=8.dp)) {
        Text("Tutorial sample",color=SdmGoldHigh,fontSize=11.sp,modifier=Modifier.padding(bottom=8.dp))
        if(step.chapter==TourChapter.Downloads && step.number==1 && !compact) {
            DownloadStatusCard(listOf(sampleDownload(),sampleDownload(1)),420000000,740000,16)
            Spacer(Modifier.height(12.dp))
        }
        if(step.chapter==TourChapter.Details && !compact) DetailsHero(mapDownloadToDetailsPresentation(sampleDownload(),1))
        Target(compact,onBounds) {
            when(step.chapter) {
                TourChapter.Downloads -> when(step.number) {
                    1 -> DownloadTabs(DownloadCategory.entries.firstOrNull{it.label==s.selected}?:DownloadCategory.All,
                        mapOf(DownloadCategory.All to 2,DownloadCategory.Queued to 2,DownloadCategory.Completed to 0),{s.selected=it.label},
                        {onFeedback("Sample downloads resumed.")},{onFeedback("Sample downloads paused.")},{onFeedback("Sample completed records cleared.")},
                        {onFeedback("Import sample links only.")},{onFeedback("Export sample links only.")})
                    2 -> { Field("Download links (one per line)",s); Actions("Paste","Add another link") { label -> s.text=if(label=="Paste")"https://example.com/sample.zip" else s.text+"\nhttps://example.com/sample.mp4";onFeedback("Sample links only — no network request.") } }
                    3 -> { Label("Destination on phone","Download/SDM");Options(listOf("Add to queue","Start now"),s);Actions("Choose folder","Add download"){onFeedback(if(it=="Choose folder")"Try the folder picker in Settings → Save location." else "Sample added. Your real queue is unchanged.")} }
                    4 -> {
                        DownloadCard(mapDownloadToCard(sampleDownload(),1,740000),selected=s.toggle,selectionMode=true,onOpen={},onToggleSelection={s.toggle=!s.toggle},onAction={s.toggle=!s.toggle})
                        Actions("Pause / Resume","Priority","Remove") { label -> if(label=="Remove")s.confirm=true else {s.toggle=!s.toggle;onFeedback("$label applied to the sample only.")} }
                        if(s.confirm) RemovalChoices {onFeedback(it);s.confirm=false}
                    }
                    5 -> {
                        repeat(2){i->DownloadCard(mapDownloadToCard(sampleDownload(if(s.reversed)1-i else i).copy(state=DownloadState.QUEUED,downloadedBytes=0),1,0),selected=true,selectionMode=true,reorderEnabled=true,
                            dragOffset=if(i==0)s.drag else 0f,onDragBy={s.drag+=it},onDragEnd={s.reversed=!s.reversed;s.drag=0f;onFeedback("Sample queue reordered.")},onDragCancel={s.drag=0f},onOpen={},onAction={})}
                        Actions("Move to top"){s.reversed=!s.reversed;onFeedback("Sample group moved to top.")}
                    }
                }
                TourChapter.Details -> when(step.number) {
                    1 -> {Label("740 KB/s","16 connections · 01:05 remaining");SpeedChart();Label("Technical information","example.com · HTTPS · Range supported");Label("Request headers / Segments","Sample headers · 16 active segments")}
                    2 -> {Actions(if(s.toggle)"Resume" else "Pause","Cancel"){if(it=="Cancel")s.confirm=true else{s.toggle=!s.toggle;onFeedback("Sample ${if(s.toggle)"paused" else "resumed"}; progress remains 42%.")}};if(s.confirm)Actions("Keep download","Confirm cancel"){s.confirm=false;onFeedback("Sample cancellation only; partial progress is retained.")}}
                    3 -> Actions("Priority","Copy link","Open folder"){s.toggle=!s.toggle;onFeedback(when(it){"Copy link"->"Sample link: https://example.com/sample.zip";"Open folder"->"Sample location: Download/SDM";else->"Sample priority changed."})}
                    4 -> {Field("File name or expected SHA-256",s);Actions("Rename","Verify checksum","Try mismatch"){onFeedback(when(it){"Rename"->"Sample renamed; existing files are unchanged.";"Verify checksum"->"Sample checksum matches: SHA-256 verified.";else->"Sample checksum mismatch: the bytes differ."})}}
                    5 -> ScheduleSample(s,onFeedback)
                    6 -> {Field("Individual speed limit",s);Options(listOf("KB/s","MB/s","Unlimited"),s);Actions("Apply limit"){onFeedback("Sample limit applied. Orange indicator identifies this file.")}}
                    7 -> {Field("New download link",s);Actions("Check link","Try changed file"){s.confirm=it=="Try changed file";onFeedback(if(s.confirm)"Different file. Restart needs your explicit consent." else "Same sample file: progress remains 42%.")};if(s.confirm)Actions("Keep progress","Restart from zero"){onFeedback(if(it=="Restart from zero")"Sample restarted from zero after consent." else "Restart cancelled; sample progress kept.");s.confirm=false}}
                }
                TourChapter.Torrents -> when(step.number) {
                    1 -> {Field("Magnet link",s);Actions("Choose .torrent file","Resolve metadata"){onFeedback("Bundled sample torrent metadata is ready. No peer connection.")}}
                    2 -> {Label("Tutorial movies","2 files · 1.4 GB");listOf("Video 1.mp4","Video 2.mp4","Extras.txt").forEachIndexed{i,name->Row(verticalAlignment=Alignment.CenterVertically){Checkbox(checked=if(i==2)s.toggle else !s.toggle,onCheckedChange={s.toggle=!s.toggle});Text(name,fontSize=13.sp)}};ToggleRow(SdmIcons.Link,"Seed after download","Optional sharing",s.toggle){s.toggle=it};Actions("Add to queue"){onFeedback("Selected sample files added to the sample queue.")}}
                    3 -> {Options(listOf("Overview","Files","Peers"),s);Label(s.selected,when(s.selected){"Peers"->"Sample peer · 740 KB/s ↓ · 120 KB/s ↑";"Files"->"Video 1.mp4 · 100%\nVideo 2.mp4 · 36%";else->"68% · 24 peers · 8 seeds"});SpeedChart()}
                    4 -> {Label(if(s.toggle)"Seeding stopped" else "Seeding","100% · completed files kept");Actions("Pause / Resume","Stop seeding"){s.toggle=!s.toggle;onFeedback("Sharing changed for the sample only. Completed files are kept.")}}
                }
                TourChapter.Browser -> when(step.number) {
                    1 -> {Field("Address or search",s);Actions("Back","Forward","Reload"){onFeedback("Sample page $it — no website is loaded.")};Label("Example page","Offline tutorial preview")}
                    2 -> {Options(listOf("Normal","Private"),s);Label(s.selected,if(s.selected=="Private")"Private browsing does not save history." else "Normal visits can appear in history.")}
                    3 -> {Label("Tabs","${s.count} sample tabs");Actions("New tab","Switch tab","Close tab"){when(it){"New tab"->s.count++;"Close tab"->s.count=(s.count-1).coerceAtLeast(1);else->s.toggle=!s.toggle};onFeedback("${s.count} sample tabs. Real tabs are unchanged.")}}
                    4 -> {Options(listOf("History","Bookmarks"),s);Label("Example page","example.com — sample only");Actions("Add bookmark","Open sample page"){onFeedback("Sample bookmark/page only; real history and bookmarks are unchanged.")}}
                    5 -> {Label("Sample archive.zip","example.com · 12 MB");Actions("Download link"){onFeedback("Sample link handed to the sample queue for review.")}}
                }
                TourChapter.Files -> when(step.number) {
                    1 -> {Field("Search files",s);Options(listOf("All","Video","Audio","Documents"),s);Actions("Sort by name","Select all"){s.toggle=!s.toggle;onFeedback("Sample file selection/sort changed.")};Label("Sample archive.zip","12 MB · Tutorial sample")}
                    2 -> {Label("Sample video.mp4","700 MB · Tutorial sample");Actions("Open","Share","Preview"){onFeedback("$it: offline sample preview. No external app is opened.")};Label("Sample preview","▶ 00:00 / 00:10")}
                    3 -> {Field("File or folder name",s);Label("Download / SDM","Tutorials\nVideos");Actions("Rename","New folder","Move here"){onFeedback("$it completed for the sample. Existing names are never overwritten.")}}
                    4 -> {Label("Sample document.pdf","3.2 MB · Tutorial sample");Actions("Delete file","Remove record"){s.confirm=true};if(s.confirm)RemovalChoices{onFeedback(it);s.confirm=false}}
                }
                TourChapter.Settings -> when(step.number) {
                    1 -> {Label("Save location","Download/SDM");Actions("Continue on phone"){picker.launch(null)};ToggleRow(SdmIcons.Folder,"Automatic folders","Save each file type in its own folder",s.toggle){s.toggle=it};Options(listOf("Video","Audio","Documents"),s);Actions("Rename save folder"){onFeedback("Sample folder renamed with files and progress preserved.")}}
                    2 -> {Label("Connections per download","Maximum streams, not a promise of active connections");NumberGrid(listOf(8,16,24,32),s.selected.toIntOrNull()?:16,4){s.selected=it.toString()};Label("Simultaneous downloads",s.count.toString());NumberGrid((1..10).toList(),s.count,5){s.count=it}}
                    3 -> {
                        val delays=AutomaticRetrySettings.DELAYS
                        Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                            TimeWheel("Retries",11,s.count,Modifier.weight(1f)){s.count=it}
                            TimeWheel("Seconds",delays.size,delays.indexOf(s.delaySeconds),Modifier.weight(1f),valueLabel={delays[it].toString()}){s.delaySeconds=delays[it]}
                        }
                        Text("Manual pauses and permanent errors are never retried.",color=SdmMuted,fontSize=12.sp)
                        Actions("Save sample retry"){onFeedback("Sample retry: ${s.count} attempts, ${s.delaySeconds}s delay.")}
                    }
                    4 -> {ToggleRow(SdmIcons.Wifi,"Wi-Fi only","Pause on mobile data",s.toggle){s.toggle=it};Options(listOf("Unlimited","1 MB/s","2 MB/s"),s);Label("Global limit","Shared by active HTTP and torrent downloads")}
                    5 -> ScheduleSample(s,onFeedback)
                    6 -> {Options(listOf("Black & Gold","Light & Gold"),s){onTheme(it=="Light & Gold")};ToggleRow(SdmIcons.Notifications,"Download complete","Completion notification",s.toggle){s.toggle=it};Label("Language","English only");Actions("About","Reset settings"){if(it=="Reset settings")s.confirm=true else onFeedback("SDM · Sibi Download Manager")};if(s.confirm)Actions("Keep settings","Confirm reset"){s.confirm=false;onFeedback("Only tutorial samples reset.")}}
                    7 -> {Label("Backup preview","3 downloads · 1 duplicate skipped");Label("sample.zip · sample.mp4","Existing downloads will be kept");ToggleRow(SdmIcons.Folder,"Restore settings","Optional",s.toggle){s.toggle=it};Actions("Import links","Export links","Restore sample"){onFeedback("$it: sample review complete. No document is read or written.")}}
                    8 -> {ToggleRow(SdmIcons.Wifi,"Enable web panel","Tutorial sample only",s.toggle){s.toggle=it};Label("LAN address","http://192.168.1.23:8081");Field("Port",s);Actions("Generate pairing code","Revoke browser"){onFeedback(if(it=="Revoke browser")"Sample browser access ended." else "Sample pairing request. Approve real browsers from your phone.")}}
                }
            }
        }
        if(!compact && step.chapter !in setOf(TourChapter.Details,TourChapter.Settings)) {
            Spacer(Modifier.height(16.dp));SampleDownloadCards()
        }
        Spacer(Modifier.height(300.dp))
    }
}
@Composable private fun Target(compact:Boolean,onBounds:(Rect)->Unit,content:@Composable ColumnScope.()->Unit) {
    Surface(Modifier.testTag("tour-target").fillMaxWidth().heightIn(max=if(compact)150.dp else 260.dp).then(Modifier.semantics{contentDescription="Tutorial practice area"}).onGloballyPositioned{onBounds(it.boundsInRoot())},color=SdmSurface,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,SdmLine)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp),content=content)
    }
}
@Composable private fun Field(label:String,s:TourSamples) {
    Text(label,color=SdmMuted,fontSize=12.sp)
    BasicTextField(s.text,{s.text=it},modifier=Modifier.fillMaxWidth().heightIn(min=48.dp).border(1.dp,SdmLine,RoundedCornerShape(12.dp)).padding(12.dp).semantics{contentDescription=label},textStyle=LocalTextStyle.current.copy(color=SdmText,fontSize=14.sp),cursorBrush=SolidColor(SdmGold))
}
@Composable private fun Label(title:String,body:String) {Column(verticalArrangement=Arrangement.spacedBy(4.dp)){Text(title,fontWeight=FontWeight.Bold,fontSize=14.sp);Text(body,color=SdmMuted,fontSize=12.sp)}}
@Composable private fun Actions(vararg labels:String,onAction:(String)->Unit) {Column(verticalArrangement=Arrangement.spacedBy(8.dp)){labels.toList().chunked(2).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{label->TourButton(label,false,Modifier.weight(1f)){onAction(label)}}}}}}
@Composable private fun Options(labels:List<String>,s:TourSamples,onSelect:(String)->Unit = {}) {Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){labels.forEach{label->TourButton(label,s.selected==label){s.selected=label;onSelect(label)}}}}
@Composable private fun Counter(label:String,s:TourSamples,range:IntRange) {Label(label,s.count.toString());Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){TourButton("Decrease $label",false,Modifier.weight(1f)){s.count=(s.count-1).coerceIn(range)};TourButton("Increase $label",false,Modifier.weight(1f)){s.count=(s.count+1).coerceIn(range)}}}
@Composable private fun RemovalChoices(onFeedback:(String)->Unit) {Label("Remove sample?","Keep the completed file, or permanently delete its sample bytes.");Actions("Keep file","Delete file"){onFeedback("$it: tutorial sample only. Your files are unchanged.")}}
@Composable private fun ScheduleSample(s:TourSamples,onFeedback:(String)->Unit) {
    Options(listOf("Any time","Scheduled","Daily window"),s)
    Label("Phone time zone",java.time.ZoneId.systemDefault().id)
    Actions("Resume at ${"%02d:%02d".format(s.minute/60,s.minute%60)}","Pause at ${"%02d:%02d".format(s.endMinute/60,s.endMinute%60)}") {s.editTime=true;s.toggle=it.startsWith("Pause")}
    if(s.editTime) {
        TimeChoice(if(s.toggle)s.endMinute else s.minute){if(s.toggle)s.endMinute=it else s.minute=it;onFeedback("Time changed for this sample only.")}
        TourButton("Done choosing time",true){s.editTime=false}
    }
}
@Composable private fun SpeedChart() {val gold=SdmGold;val line=SdmLine;Column{Text("Speed history · Last 60 sec",fontSize=12.sp);Canvas(Modifier.fillMaxWidth().height(72.dp)){repeat(3){drawLine(line,androidx.compose.ui.geometry.Offset(0f,size.height*it/3),androidx.compose.ui.geometry.Offset(size.width,size.height*it/3))};val p=androidx.compose.ui.graphics.Path();repeat(30){i->val x=size.width*i/29;val y=size.height*(.65f-(i%7)*.06f);if(i==0)p.moveTo(x,y)else p.lineTo(x,y)};drawPath(p,gold,style=androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx()))}}}
