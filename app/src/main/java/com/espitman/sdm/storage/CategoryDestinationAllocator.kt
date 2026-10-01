package com.espitman.sdm.storage

import java.io.File

class CategoryDestinationAllocator(
    private val settings:()->CategoryFolderSettings,
    private val fallback:DestinationAllocator,
    private val appDirectory:()->File,
    private val grants:TreeUriGrantStore,
    private val trees:UserTreeAccess,
    private val onUnavailable:(FileCategory)->Unit = {},
    private val onAvailable:(FileCategory)->Unit = {},
):DestinationAllocator {
    override fun allocate(candidateFileName:String)=allocate(candidateFileName,null)
    override fun allocate(candidateFileName:String,mimeType:String?):AllocatedDownloadDestination {
        val policy=settings()
        val category=FileCategory.classify(candidateFileName,mimeType)
        val rule=policy.rules[category].takeIf {policy.enabled} ?: return fallback.allocate(candidateFileName,mimeType)
        val inspection=try {if(grants.hasReadWrite(rule.treeUri)) trees.inspect(rule.treeUri) else null} catch(_:Exception) {null}
        if(inspection?.state!=UserTreeState.Writable) {
            onUnavailable(category)
            return fallback.allocate(candidateFileName,mimeType)
        }
        onAvailable(category)
        // Capture this rule in the record at submission; later edits cannot move active files.
        return AppPrivateDestinationAllocator.reserveInDirectory(
            SaveLocationDestinationAllocator.stagingDirectory(appDirectory()),candidateFileName,
            inspection.childDisplayNames + SaveLocationDestinationAllocator.stagingDirectory(appDirectory()).list().orEmpty(),
            rule.treeUri,rule.label)
    }
}
