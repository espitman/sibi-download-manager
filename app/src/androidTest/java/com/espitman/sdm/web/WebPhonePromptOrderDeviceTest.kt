package com.espitman.sdm.web

import android.content.Intent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.storage.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebPhonePromptOrderDeviceTest {
    @Test fun onlyOnePhoneConfirmationIsVisibleAndPendingRequestsSurviveTheirTurn() = runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        WebPanelRuntime.initialize(context)
        val access=WebPanelRuntime.access
        // Kotlin internal accessors include the build variant; the installed release
        // must be exercised without linking the debug test APK to its debug suffix.
        fun runtimeProperty(name: String): Any = WebPanelRuntime::class.java.methods.single {
            it.name.startsWith("get${name}\$") && it.parameterCount == 0
        }.invoke(WebPanelRuntime)
        val folders=runtimeProperty("FolderAccess") as WebFolderAccess
        val handoff=runtimeProperty("FileHandoff") as WebFileHandoff
        val automation=instrumentation.uiAutomation
        val previousFlags=automation.serviceInfo.flags
        assumeTrue(access.requests.value.isEmpty()&&folders.pending.value==null&&handoff.pending.value==null)
        val (request,ticket)=access.request("SDM prompt-order fixture","127.0.0.1")
        check(access.approve(request.id))
        val browser=access.authorize(checkNotNull(access.collect(request.id,ticket)))
        var folder:String?=null;var approval:String?=null;var file:String?=null
        fun text(node:AccessibilityNodeInfo?):List<String> {
            if(node==null)return emptyList()
            return listOfNotNull(node.text?.toString())+(0 until node.childCount).flatMap { text(node.getChild(it)) }
        }
        suspend fun expect(title:String,absent:List<String>) {
            var observed=emptyList<String>()
            try { withTimeout(10_000) {
                while(true) {
                    val roots=automation.windows.mapNotNull { it.root }.filter { it.packageName?.toString()==context.packageName }
                    val texts=roots.flatMap(::text)
                    observed=texts
                    if(title in texts) { assertTrue("Overlapping prompt: $texts",absent.none { it in texts });break }
                    delay(100)
                }
            } } catch (timeout: TimeoutCancellationException) {
                throw AssertionError("Missing phone prompt '$title'; observed: $observed",timeout)
            }
        }
        try {
            automation.serviceInfo=automation.serviceInfo.apply { flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
            context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            file=handoff.request(browser.id,browser.browser,CompletedFileIdentity("fixture","content://fixture/file","text/plain","owned.txt"),CompletedFileAction.Open).id
            expect("Open file on phone",listOf("Choose download folder","Web panel access"))
            folder=folders.create(browser.id,null).getString("id")
            expect("Choose download folder",listOf("Open file on phone","Web panel access"))
            approval=access.request("SDM pending fixture","127.0.0.1").first.id
            expect("Web panel access",listOf("Choose download folder","Open file on phone"))
            access.deny(approval!!);approval=null
            expect("Choose download folder",listOf("Open file on phone","Web panel access"))
            folders.cancel(browser.id,folder!!);folder=null
            expect("Open file on phone",listOf("Choose download folder","Web panel access"))
            assertEquals(file,handoff.pending.value?.id)
        } finally {
            approval?.let(access::deny)
            folder?.let { folders.cancel(browser.id,it) }
            file?.let(handoff::dismiss)
            access.revoke(browser.id)
            automation.serviceInfo=automation.serviceInfo.apply { flags=previousFlags }
        }
    }
}
