package com.espitman.sdm.ui.tutorial

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.*
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.compose.ui.window.*
import com.espitman.sdm.ui.*
import com.espitman.sdm.ui.theme.*

/** A separate modal sample surface: the real app composition and its saved view stay intact. */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable internal fun TutorialHost(state:TourState,onChange:(TourState)->Unit) {
    var sampleLight by remember { mutableStateOf(false) }
    val originalLight = SdmIsLight
    val inheritedDensity=LocalDensity.current
    var themeChosen by remember { mutableStateOf(false) }
    SdmSampleTheme(if(themeChosen) sampleLight else originalLight) {
    Dialog(onDismissRequest={onChange(state.close())},properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        CompositionLocalProvider(LocalDensity provides inheritedDensity) {
        val dialogView=LocalView.current
        val dialogLight=SdmIsLight
        SideEffect {
            (dialogView.parent as? DialogWindowProvider)?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window,dialogView).apply {
                    isAppearanceLightStatusBars=dialogLight
                    isAppearanceLightNavigationBars=dialogLight
                }
            }
        }
        Surface(Modifier.fillMaxSize().imePadding().onPreviewKeyEvent {
            if(it.key==Key.Escape && it.type==KeyEventType.KeyUp){onChange(state.close());true}else false
        },color=SdmBackground,contentColor=SdmText) {
            BoxWithConstraints(Modifier.fillMaxSize().semantics{isTraversalGroup=true;testTagsAsResourceId=true}) {
                val keyboard=WindowInsets.ime.getBottom(LocalDensity.current)>0
                Column(Modifier.fillMaxSize()) {
                    if(!keyboard) AppHeader(if(state.phase==TourPhase.Running) when(state.chapter){TourChapter.Details->"Download details";TourChapter.Torrents->"Torrent";else->state.chapter.title} else "Downloads",showSearch=false,showMore=false)
                    else Text("Add download",fontWeight=FontWeight.Bold,modifier=Modifier.statusBarsPadding().padding(16.dp))
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        if(state.phase==TourPhase.Running) RunningTour(state,onChange){sampleLight=it;themeChosen=true}
                        else {
                            Column(Modifier.padding(16.dp).fillMaxSize()) {
                                SampleDownloadCards()
                            }
                            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha=.65f)))
                            TourMenu(state,onChange,Modifier.align(Alignment.Center).padding(20.dp))
                        }
                    }
                    if(!keyboard && inheritedDensity.fontScale<=1.3f) Box(Modifier.fillMaxWidth().height(108.dp + with(LocalDensity.current){WindowInsets.navigationBars.getBottom(this).toDp()})) {
                        BottomNavigation(when(state.chapter){TourChapter.Browser->Destination.Browser;TourChapter.Files->Destination.Files;TourChapter.Settings->Destination.Settings;else->Destination.Downloads},
                            { d -> TourChapter.entries.find{it.title==d.label}?.let{onChange(state.start(it))} },Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
        }
    }
    }
    }
}

@Composable private fun TourMenu(state:TourState,onChange:(TourState)->Unit,modifier:Modifier) {
    val resume=state.phase==TourPhase.Resume
    val complete=state.phase==TourPhase.Complete
    Surface(modifier.widthIn(max=420.dp).fillMaxWidth().heightIn(max=650.dp),color=SdmSurfaceAlt,contentColor=SdmText,shape=RoundedCornerShape(20.dp),border=BorderStroke(1.dp,SdmLine)) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(48.dp).align(Alignment.CenterHorizontally).border(1.dp,SdmGold,if(complete)androidx.compose.foundation.shape.CircleShape else RoundedCornerShape(12.dp)),contentAlignment=Alignment.Center) {
                if(!complete && !resume) Text("SD",fontWeight=FontWeight.ExtraBold,color=SdmGoldHigh,fontSize=20.sp)
                else Icon(if(complete)SdmIcons.Check else SdmIcons.Schedule,null,tint=SdmGold,modifier=Modifier.size(28.dp))
            }
            Text(if(complete)"You’re ready" else if(resume)"Continue your tour?" else if(state.phase==TourPhase.Chapters)"Choose a chapter" else "Get to know SDM",fontWeight=FontWeight.ExtraBold,fontSize=22.sp)
            Text(if(complete)"You can restart any chapter from Settings." else if(resume)"Pick up from ${state.chapter.title}, step ${state.number} of ${state.chapter.count}." else "A quick hands-on tour with safe sample downloads.",color=SdmMuted,fontSize=14.sp)
            if(!resume) TourChapter.entries.forEach { chapter ->
                Row(Modifier.fillMaxWidth().heightIn(min=44.dp).testTag("tour-chapter-${chapter.name}").clickable{onChange(state.start(chapter))}.padding(vertical=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Icon(if(chapter in state.completed)SdmIcons.Check else chapterIcon(chapter),null,tint=SdmGold,modifier=Modifier.size(22.dp))
                    Text(chapter.title,Modifier.padding(start=12.dp).weight(1f),fontSize=15.sp)
                    Text("${chapter.count} steps",color=SdmMuted,fontSize=12.sp)
                }
            }
            if(resume) {
                TourButton("Resume tour",true,Modifier.fillMaxWidth()){onChange(state.copy(phase=TourPhase.Running))}
                TourButton("Start over",false,Modifier.fillMaxWidth()){onChange(TourState())}
                TourButton("Not now",false,Modifier.fillMaxWidth()){onChange(state.close())}
            } else if(complete || state.phase==TourPhase.Chapters) {
                TourButton("Done",true,Modifier.fillMaxWidth()){onChange(state.close())}
            } else Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                TourButton("Not now",false,Modifier.weight(1f)){onChange(state.close())}
                TourButton("Start tour",true,Modifier.weight(1f)){onChange(state.start(TourChapter.Downloads))}
            }
        }
    }
}

@Composable private fun RunningTour(state:TourState,onChange:(TourState)->Unit,onTheme:(Boolean)->Unit) {
    key(state.step.id) {
        var raw by remember {mutableStateOf<Rect?>(null)}
        var origin by remember {mutableStateOf(Offset.Zero)}
        var coachHeight by remember {mutableIntStateOf(0)}
        var feedback by remember {mutableStateOf("")}
        var missing by remember { mutableStateOf(false) }
        LaunchedEffect(raw) {
            if(raw==null){kotlinx.coroutines.delay(500);missing=true}else missing=false
        }
        val samples = remember { TourSamples().apply {
            text = when {
                state.chapter==TourChapter.Settings && state.number==8 -> "8081"
                state.chapter==TourChapter.Details && state.number==6 -> "300"
                state.chapter==TourChapter.Details && state.number==4 -> "Sample archive.zip"
                state.chapter==TourChapter.Files && state.number==3 -> "Sample video.mp4"
                state.chapter==TourChapter.Torrents && state.number==1 -> "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
                else -> text
            }
        } }
        val density=LocalDensity.current
        BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned{origin=it.positionInRoot()}) {
            val availableHeight=maxHeight
            var stageOrigin by remember { mutableStateOf(Offset.Zero) }
            var needsSplit by remember(maxHeight, density.fontScale) { mutableStateOf(false) }
            val compact=maxHeight<420.dp || density.fontScale>1.3f || needsSplit
            val gold=SdmGold
            val target=raw?.translate(-origin)
            val gap=with(density){12.dp.toPx()}
            val heightPx=with(density){availableHeight.toPx()}
            LaunchedEffect(target,coachHeight) {
                if(!compact && target!=null && coachHeight>0 && !canFloatCoach(target.top,target.bottom,coachHeight.toFloat(),heightPx,gap)) needsSplit=true
            }
            if(compact) {
                Column(Modifier.fillMaxSize().padding(horizontal=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.weight(1f).fillMaxWidth().onGloballyPositioned{stageOrigin=it.positionInRoot()}) {
                        TutorialScene(state.step,true,samples,{raw=it},{feedback=it},onTheme)
                        Spotlight(raw?.translate(-stageOrigin),gold)
                    }
                    Coach(state,if(missing)"This control is unavailable here. Use Next to continue." else feedback,onChange,Modifier.fillMaxWidth().heightIn(max=availableHeight*.58f))
                }
            } else {
                TutorialScene(state.step,false,samples,{raw=it},{feedback=it},onTheme)
                Spotlight(target,gold)
                val y=target?.let { if(it.bottom+gap+coachHeight<=with(density){maxHeight.toPx()})it.bottom+gap else (it.top-gap-coachHeight).coerceAtLeast(gap) } ?: gap
                Column(Modifier.offset{IntOffset(0,y.toInt())}.padding(horizontal=16.dp).fillMaxWidth().onSizeChanged{coachHeight=it.height}) {
                    val below=target==null || y>=target.bottom
                    if(below) CoachPointer(true,target?.center?.x ?: 32f)
                    Coach(state,if(missing)"This control is unavailable here. Use Next to continue." else feedback,onChange,Modifier.fillMaxWidth().heightIn(max=availableHeight*.55f))
                    if(!below) CoachPointer(false,target?.center?.x ?: 32f)
                }
            }
        }
    }
}

@Composable private fun Spotlight(target:Rect?,gold:Color) {
    Canvas(Modifier.fillMaxSize()) {
        val hole=target?.intersect(Rect(Offset.Zero,size))
        val path=Path().apply {
            fillType=PathFillType.EvenOdd
            addRect(Rect(Offset.Zero,size))
            hole?.takeIf{it.width>0&&it.height>0}?.let{addRoundRect(RoundRect(it,CornerRadius(12.dp.toPx())))}
        }
        drawPath(path,Color.Black.copy(alpha=.65f))
        hole?.takeIf{it.width>0&&it.height>0}?.let{drawRoundRect(gold,it.topLeft,it.size,CornerRadius(12.dp.toPx()),style=Stroke(2.dp.toPx()))}
    }
}
@Composable private fun Coach(state:TourState,feedback:String,onChange:(TourState)->Unit,modifier:Modifier) {
    Surface(modifier.testTag("tour-coach").semantics{liveRegion=LiveRegionMode.Polite;isTraversalGroup=true},color=SdmSurfaceAlt,contentColor=SdmText,shape=RoundedCornerShape(16.dp),border=BorderStroke(1.dp,SdmLine)) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f,false).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text("${state.chapter.title.uppercase()} · ${state.number} / ${state.chapter.count}",color=SdmGoldHigh,fontSize=11.sp,fontWeight=FontWeight.Bold,modifier=Modifier.clickable{onChange(state.copy(phase=TourPhase.Chapters))})
                Text(state.step.title,fontWeight=FontWeight.ExtraBold,fontSize=18.sp,lineHeight=23.sp,modifier=Modifier.semantics{heading()})
                Text(state.step.body,fontSize=14.sp,lineHeight=20.sp)
                if(feedback.isNotEmpty()) Text(feedback,color=SdmGoldHigh,fontSize=12.sp,modifier=Modifier.semantics{liveRegion=LiveRegionMode.Polite})
                Row(horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                    repeat(state.chapter.count) { index -> Box(Modifier.weight(1f).height(4.dp).background(if(index<state.number)SdmGold else SdmLine,RoundedCornerShape(3.dp))) }
                }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                TourButton("Back",false,Modifier.weight(1f)){onChange(state.back())}
                TourButton(if(state.number==state.chapter.count)"Finish chapter" else "Next",true,Modifier.weight(1.25f)){onChange(state.next())}
                TextButton(onClick={onChange(state.close())},modifier=Modifier.heightIn(min=44.dp)){Text("Skip",color=SdmMuted,fontSize=13.sp)}
            }
        }
    }
}
@Composable internal fun TourButton(text:String,primary:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    Surface(onClick=onClick,modifier=modifier.heightIn(min=44.dp),shape=RoundedCornerShape(12.dp),color=if(primary)SdmGold else SdmSurface,
        contentColor=if(primary)Color(0xFF080808)else SdmText,border=BorderStroke(1.dp,if(primary)SdmGold else SdmLine)) {
        Box(Modifier.padding(horizontal=12.dp,vertical=12.dp),contentAlignment=Alignment.Center){Text(text,fontSize=13.sp,fontWeight=FontWeight.Bold)}
    }
}
internal fun chapterIcon(chapter:TourChapter)=when(chapter){TourChapter.Downloads->SdmIcons.Download;TourChapter.Details->SdmIcons.File;TourChapter.Torrents->SdmIcons.Link;TourChapter.Browser->SdmIcons.Browser;TourChapter.Files->SdmIcons.Folder;TourChapter.Settings->SdmIcons.Settings}

@Composable private fun CoachPointer(up:Boolean,x:Float) {
    val color=SdmLine
    Canvas(Modifier.fillMaxWidth().height(8.dp)) {
        val middle=(x-16.dp.toPx()).coerceIn(16.dp.toPx(),(size.width-16.dp.toPx()).coerceAtLeast(16.dp.toPx()))
        val p=Path().apply{moveTo(middle-8.dp.toPx(),if(up)size.height else 0f);lineTo(middle,if(up)0f else size.height);lineTo(middle+8.dp.toPx(),if(up)size.height else 0f);close()}
        drawPath(p,color)
    }
}

internal fun canFloatCoach(top:Float,bottom:Float,coachHeight:Float,height:Float,gap:Float)=
    bottom+gap+coachHeight<=height || top-gap-coachHeight>=gap
