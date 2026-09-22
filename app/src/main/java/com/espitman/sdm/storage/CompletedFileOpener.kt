package com.espitman.sdm.storage

data class ShareableCompletedFile(
    val uriString: String,
    val persistedMimeType: String?,
    val fileName: String,
    val contentResolverType: String? = null,
)

class CompletedFileOpener(
    private val resolve: (CompletedFileIdentity) -> ShareableCompletedFile?,
    private val extensionMime: (String) -> String?,
    private val hasHandler: (CompletedFileIntentSpec) -> Boolean,
    private val start: (CompletedFileIntentSpec) -> Unit,
) {
    fun perform(
        action: CompletedFileAction,
        identity: CompletedFileIdentity,
    ): CompletedFileActionResult {
        val shareable = try {
            resolve(identity)
        } catch (error: Throwable) {
            return CompletedFileIntents.fromFailure(action, error)
        } ?: return CompletedFileActionResult.FileUnavailable
        if (!CompletedFileDestination.isShareableContentUri(shareable.uriString)) {
            return CompletedFileActionResult.FileUnavailable
        }
        val mimeType = CompletedFileMime.resolve(
            contentResolverType = shareable.contentResolverType,
            persistedMimeType = shareable.persistedMimeType,
            fileName = shareable.fileName,
            extensionMime = extensionMime,
        )
        val spec = try {
            CompletedFileIntents.forAction(action, shareable.uriString, mimeType)
        } catch (error: Throwable) {
            return CompletedFileIntents.fromFailure(action, error)
        }
        if (!hasHandler(spec)) {
            return CompletedFileIntents.noHandler(action)
        }
        return try {
            start(spec)
            CompletedFileActionResult.Launched
        } catch (error: Throwable) {
            CompletedFileIntents.fromFailure(action, error)
        }
    }
}
