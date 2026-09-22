package com.espitman.sdm.storage

data class CompletedFileIntentSpec(
    val action: String,
    val mimeType: String,
    val dataUri: String?,
    val extraStreamUri: String?,
    val clipDataUri: String?,
    val flags: Int,
    val categories: Set<String>,
    val chooserTitle: String?,
)

enum class CompletedFileAction {
    Open,
    Share,
}

sealed interface CompletedFileActionResult {
    val message: String?

    data object Launched : CompletedFileActionResult {
        override val message: String? = null
    }

    data object NoAppOpen : CompletedFileActionResult {
        override val message: String = "No app can open this file"
    }

    data object NoAppShare : CompletedFileActionResult {
        override val message: String = "No app can share this file"
    }

    data object FileUnavailable : CompletedFileActionResult {
        override val message: String = "File is no longer available"
    }
}

object CompletedFileIntents {
    const val ACTION_VIEW = "android.intent.action.VIEW"
    const val ACTION_SEND = "android.intent.action.SEND"
    const val CATEGORY_OPENABLE = "android.intent.category.OPENABLE"
    const val EXTRA_STREAM = "android.intent.extra.STREAM"
    const val FLAG_GRANT_READ = 0x00000001
    const val CHOOSER_TITLE = "Share file"

    fun forAction(action: CompletedFileAction, uriString: String, mimeType: String): CompletedFileIntentSpec =
        when (action) {
            CompletedFileAction.Open -> open(uriString, mimeType)
            CompletedFileAction.Share -> share(uriString, mimeType)
        }

    fun open(uriString: String, mimeType: String): CompletedFileIntentSpec {
        val uri = requireContentUri(uriString)
        return CompletedFileIntentSpec(
            action = ACTION_VIEW,
            mimeType = mimeType,
            dataUri = uri,
            extraStreamUri = null,
            clipDataUri = uri,
            flags = FLAG_GRANT_READ,
            categories = setOf(CATEGORY_OPENABLE),
            chooserTitle = null,
        )
    }

    fun share(uriString: String, mimeType: String): CompletedFileIntentSpec {
        val uri = requireContentUri(uriString)
        return CompletedFileIntentSpec(
            action = ACTION_SEND,
            mimeType = mimeType,
            dataUri = null,
            extraStreamUri = uri,
            clipDataUri = uri,
            flags = FLAG_GRANT_READ,
            categories = emptySet(),
            chooserTitle = CHOOSER_TITLE,
        )
    }

    fun noHandler(action: CompletedFileAction): CompletedFileActionResult = when (action) {
        CompletedFileAction.Open -> CompletedFileActionResult.NoAppOpen
        CompletedFileAction.Share -> CompletedFileActionResult.NoAppShare
    }

    fun fromFailure(action: CompletedFileAction, error: Throwable): CompletedFileActionResult {
        return when (error) {
            is android.content.ActivityNotFoundException -> noHandler(action)
            is SecurityException,
            is IllegalArgumentException,
            is java.io.FileNotFoundException,
            -> CompletedFileActionResult.FileUnavailable
            else -> CompletedFileActionResult.FileUnavailable
        }
    }

    private fun requireContentUri(uriString: String): String {
        val trimmed = uriString.trim()
        require(CompletedFileDestination.isShareableContentUri(trimmed)) {
            "Shareable file URI must be a content URI"
        }
        require(!trimmed.startsWith("file:", ignoreCase = true)) {
            "file:// URIs must not be exposed"
        }
        return trimmed
    }
}
