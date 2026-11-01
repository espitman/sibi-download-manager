package com.espitman.sdm.web

import com.espitman.sdm.storage.CompletedFileAction
import com.espitman.sdm.storage.CompletedFileIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A browser requests an operation; the foreground phone user chooses whether to launch it. */
internal class WebFileHandoff(
    private val clock:()->Long=System::currentTimeMillis,
    private val approved:(String)->Boolean={ owner->WebPanelRuntime.access.browsers.value.any { it.id==owner } },
) {
    data class Request(val id:String,val owner:String,val browserName:String,val identity:CompletedFileIdentity,val action:CompletedFileAction,val index:String,val expires:Long)
    private val mutable=MutableStateFlow<Request?>(null)
    val pending=mutable.asStateFlow()
    @Synchronized fun request(owner:String,browserName:String,identity:CompletedFileIdentity,action:CompletedFileAction,index:String="main"):Request {
        expire()
        require(approved(owner)) { "Browser access was revoked" }
        mutable.value?.let {
            require(it.owner==owner&&it.identity==identity&&it.action==action&&it.index==index) { "Complete the pending file request on your phone first" }
            return it
        }
        return Request(UUID.randomUUID().toString(),owner,browserName,identity,action,index,clock()+120_000).also { mutable.value=it }
    }
    @Synchronized fun consume(id:String):Request? {
        expire()
        return mutable.value?.takeIf { it.id==id }?.also { mutable.value=null }
    }
    @Synchronized fun dismiss(id:String) { if(mutable.value?.id==id)mutable.value=null }
    @Synchronized fun expire() { mutable.value?.let { if(clock()>=it.expires||!approved(it.owner))mutable.value=null } }
    @Synchronized fun cancelAll() { mutable.value=null }
}
