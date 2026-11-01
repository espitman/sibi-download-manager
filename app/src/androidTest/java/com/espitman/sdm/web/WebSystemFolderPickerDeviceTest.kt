package com.espitman.sdm.web

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.espitman.sdm.data.AppRepositories
import com.espitman.sdm.storage.PersistableTreeUriGrants
import com.espitman.sdm.storage.SaveLocationStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Chrome and Android UI drive the request; this fixture independently checks its result. */
@RunWith(AndroidJUnit4::class)
class WebSystemFolderPickerDeviceTest {
    @Test fun externalChromeReselectsExistingRootThroughAndroidPicker() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        WebPanelRuntime.initialize(context)
        check(WebPanelRuntime.enabled(context)) { "Enable the phone panel before this test" }
        WebPanelRuntime.start(context)
        withTimeout(10_000) { while(!WebPanelRuntime.status.value.running)delay(100) }
        val folders=WebPanelRuntime::class.java.methods.single {
            it.name.startsWith("getFolderAccess\$")&&it.parameterCount==0
        }.invoke(WebPanelRuntime) as WebFolderAccess
        val store=SaveLocationStore.get(context)
        val original=store.read()
        val root=checkNotNull(original.treeUri) { "This test requires an existing granted root" }
        val grants=PersistableTreeUriGrants(context.contentResolver)
        check(grants.hasReadWrite(root))
        val originalGrants=grants.persistedReadWriteTrees().toSet()
        val repo=AppRepositories.downloads(context);repo.awaitInitialized()
        val originalRecords=repo.downloads.value
        var request:WebFolderAccess.Request?=null
        try {
            context.startActivity(Intent(context,com.espitman.sdm.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            println("CHROME_FOLDER_PICKER_READY")
            withTimeout(120_000) { while(folders.pending.value==null)delay(100) }
            request=checkNotNull(folders.pending.value)
            check(request!!.category==null)
            println("CHROME_FOLDER_PICKER_PENDING")
            withTimeout(120_000) {
                while(folders.status(request!!.owner,request!!.id).getString("state")=="pending")delay(100)
            }
            assertEquals("accepted",folders.status(request!!.owner,request!!.id).getString("state"))
            assertEquals(original,store.read())
            assertEquals(originalGrants,grants.persistedReadWriteTrees().toSet())
            assertEquals(originalRecords,repo.downloads.value)
            println("CHROME_FOLDER_PICKER_ACCEPTED")
            delay(5_000)
        } finally {
            request?.let { folders.cancel(it.owner,it.id) }
            if(store.read()!=original)store.persistUserTree(root,original.displayLabel)
        }
    }
}
