package com.espitman.sdm.ui.tutorial

import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.activity.compose.setContent
import com.espitman.sdm.MainActivity
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.ui.theme.SdmTheme
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real native accessibility actions; no Espresso clock is installed over activity bootstrap. */
class TutorialDeviceTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val automation get()=instrumentation.uiAutomation
    private fun activity():MainActivity {
        var found:MainActivity?=null
        instrumentation.runOnMainSync { found=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull() }
        return checkNotNull(found){"MainActivity is not resumed"}
    }
    private fun recreate(){val a=activity();instrumentation.runOnMainSync{a.recreate()};Thread.sleep(350)}
    private fun render(content: @androidx.compose.runtime.Composable () -> Unit){val a=activity();instrumentation.runOnMainSync{a.setContent(content=content)}}
    private lateinit var oldTutorial:Map<String,*>
    private lateinit var original:List<com.espitman.sdm.domain.Download>
    private lateinit var preferences:Map<String,Map<String,*>>
    private lateinit var grants:Set<String>
    private fun stage(value:String){val b=Bundle();b.putString("stream","\nTutorial QA: $value\n");instrumentation.sendStatus(0,b)}
    @Before fun prepare():Unit=runBlocking {
        oldTutorial=context.getSharedPreferences("sdm_tutorial_v1",0).all.toMap()
        val repo=AppRepositories.downloads(context);kotlinx.coroutines.withTimeout(15000){repo.awaitInitialized()};original=repo.downloads.value
        preferences=listOf("sdm_settings","sdm_category_folders").associateWith{context.getSharedPreferences(it,0).all.toMap()}
        grants=context.contentResolver.persistedUriPermissions.map{it.uri.toString()+it.isReadPermission+it.isWritePermission}.toSet()
        context.getSharedPreferences("sdm_tutorial_v1",0).edit().clear().commit()
    }
    @After fun restore():Unit=runBlocking {
        runCatching { val a=activity();instrumentation.runOnMainSync{a.finish()} }
        val edit=context.getSharedPreferences("sdm_tutorial_v1",0).edit().clear()
        oldTutorial.forEach{(k,v)->when(v){is String->edit.putString(k,v);is Int->edit.putInt(k,v);is Boolean->edit.putBoolean(k,v);is Set<*>->edit.putStringSet(k,v.filterIsInstance<String>().toSet())}}
        edit.commit()
        assertEquals("Tutorial changed real downloads",original,AppRepositories.downloads(context).downloads.value)
        preferences.forEach{(name,before)->assertEquals("Tutorial changed $name",before,context.getSharedPreferences(name,0).all.toMap())}
        assertEquals(grants,context.contentResolver.persistedUriPermissions.map{it.uri.toString()+it.isReadPermission+it.isWritePermission}.toSet())
    }
    private fun all(root:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(root==null)emptyList() else listOf(root)+(0 until root.childCount).flatMap{all(root.getChild(it))}
    private fun find(label:String):AccessibilityNodeInfo? {
        val nodes=all(automation.rootInActiveWindow)
        return nodes.firstOrNull{it.viewIdResourceName==label} ?: nodes.firstOrNull{it.contentDescription?.toString()==label} ?: nodes.firstOrNull{it.text?.toString()==label}
    }
    private fun waitFor(label:String):AccessibilityNodeInfo {
        val deadline=System.currentTimeMillis()+20000
        while(System.currentTimeMillis()<deadline){find(label)?.takeIf{it.isVisibleToUser}?.let{return it};Thread.sleep(100)}
        capture("failure");throw AssertionError("Visible tutorial node missing: $label; visible labels="+all(automation.rootInActiveWindow).mapNotNull{it.text?.toString()}.take(35))
    }
    private fun seek(label:String):AccessibilityNodeInfo {
        repeat(18){find(label)?.takeIf{it.isVisibleToUser}?.let{return it}
            val target=find("tour-target")
            val scroll=all(target).firstOrNull{it.isScrollable}?:all(automation.rootInActiveWindow).firstOrNull{it.isScrollable}
            if(scroll==null)return waitFor(label)
            scroll.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);Thread.sleep(120)
        };return waitFor(label)
    }
    private fun click(label:String,scroll:Boolean=false){var node:AccessibilityNodeInfo?=if(scroll)seek(label)else waitFor(label);while(node!=null&&!node.isClickable)node=node.parent;assertTrue("Cannot click $label",node?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true);Thread.sleep(120)}
    private fun launch(){
        stage("launch activity via authorized shell")
        automation.executeShellCommand("am start -W -n ${context.packageName}/com.espitman.sdm.MainActivity").use { fd -> java.io.FileInputStream(fd.fileDescriptor).use{stage(it.bufferedReader().readText())} }
        waitFor("Get to know SDM")
    }
    private fun capture(name:String){automation.takeScreenshot()?.let{bmp->File(context.externalCacheDir,"tutorial-$name.png").outputStream().use{bmp.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)};bmp.recycle()}}
    private fun bounds(label:String)=Rect().also{waitFor(label).getBoundsInScreen(it)}
    @Test fun all33StepsAreReachableAndActionsStayIsolated() {
        launch();capture("welcome")
        TourChapter.entries.forEach { chapter ->
            click("tour-chapter-${chapter.name}",true)
            repeat(chapter.count){i->
                val step=TourCatalog.step(chapter,i+1);stage(step.id)
                waitFor(step.title);Thread.sleep(120)
                val target=bounds("tour-target");val coach=bounds("tour-coach")
                assertFalse("Coach covers ${step.id}: target=$target coach=$coach",Rect.intersects(target,coach));assertTrue(target.width()>0&&target.height()>0)
                if(chapter==TourChapter.Downloads&&i==1){val args=Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,"https://example.com/typed.zip");assertTrue(waitFor("Download links (one per line)").performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args));click("Paste")}
                if(chapter==TourChapter.Details&&i==6){click("Check link",true);waitFor("Same sample file: progress remains 42%.")}
                if(chapter==TourChapter.Settings&&i==7){click("Revoke browser",true);waitFor("Sample browser access ended.")}
                if(i==0||i==chapter.count-1)capture(step.id)
                click(if(i+1==chapter.count)"Finish chapter" else "Next")
            }
        }
        waitFor("You’re ready");capture("complete");click("Done");assertEquals(TourPhase.Hidden,TutorialStore(context).read().phase)
    }
    @Test fun recreationResumesAndSkipAndRestartPreserveTheRealView() {
        launch();click("Start tour",true);click("Next");recreate()
        waitFor("Continue your tour?");capture("resume");click("Resume tour");waitFor("Paste one link or several");click("Back");waitFor("Everything in one place");click("Skip")
        assertEquals(1,TutorialStore(context).read().number);recreate();Thread.sleep(500);assertNull(find("Continue your tour?"))
        click("Settings");click("Restart tutorial",true);waitFor("Get to know SDM");click("Not now",true);waitFor("Restart tutorial")
    }
    @Test fun largeTextUsesASeparateTargetAndScrollableCoach() {
        launch();render {SdmTheme{val base=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(base.density,2f)){TutorialHost(TourState(TourPhase.Running,TourChapter.Details,7)) {}}}}
        waitFor("Keep the progress you have");Thread.sleep(200)
        assertFalse(Rect.intersects(bounds("tour-target"),bounds("tour-coach")));waitFor("Skip");capture("large-text")
    }
    @Test fun smallViewportKeyboardAndSampleThemeStayInsideTheirOwnWindow() {
        launch()
        render {
            SdmTheme {
                val base=LocalDensity.current
                val density=context.resources.displayMetrics.widthPixels/320f
                CompositionLocalProvider(LocalDensity provides Density(density,base.fontScale)) {
                    TutorialHost(TourState(TourPhase.Running,TourChapter.Downloads,2)) {}
                }
            }
        }
        waitFor("Paste one link or several")
        click("Download links (one per line)")
        val info=automation.serviceInfo
        info.flags=info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo=info
        val until=System.currentTimeMillis()+7000
        while(System.currentTimeMillis()<until && automation.windows.none{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD})Thread.sleep(100)
        val ime=automation.windows.firstOrNull{it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD}
        assertNotNull("Sample text field should open the keyboard",ime)
        val keyboard=Rect();ime!!.getBoundsInScreen(keyboard)
        waitFor("Skip");assertFalse("Keyboard covers guide",Rect.intersects(keyboard,bounds("tour-coach")))
        capture("320-keyboard")
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN,android.view.KeyEvent.KEYCODE_BACK),true)
        automation.injectInputEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP,android.view.KeyEvent.KEYCODE_BACK),true)
        render { SdmTheme { TutorialHost(TourState(TourPhase.Running,TourChapter.Settings,6)) {} } }
        waitFor("Make SDM comfortable");click("Light & Gold",true);Thread.sleep(250);capture("light-theme")
        assertEquals(preferences.getValue("sdm_settings"),context.getSharedPreferences("sdm_settings",0).all.toMap())
    }

    @Test fun splashCompletesBeforeTheTutorialWindowOpens() {
        launch()
        val started=android.os.SystemClock.elapsedRealtime()
        render { SdmTheme { com.espitman.sdm.ui.SdmLaunchLayer(showSplash=true) {
            if(com.espitman.sdm.ui.LocalLaunchReady.current) TutorialHost(TourState()) {}
        } } }
        Thread.sleep(400)
        assertNull("Tutorial covered the original splash",find("Get to know SDM"))
        capture("original-splash")
        waitFor("Get to know SDM")
        assertTrue("Tutorial opened before the splash animation and fade completed",android.os.SystemClock.elapsedRealtime()-started>=1650)
        Thread.sleep(350) // Capture the settled dialog, rather than its platform window fade.
        capture("after-splash")
    }

}
