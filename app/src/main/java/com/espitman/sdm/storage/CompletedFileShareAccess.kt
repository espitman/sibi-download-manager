package com.espitman.sdm.storage

import android.app.Activity
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File

class CompletedFileShareAccess(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val opener = CompletedFileOpener(
        resolve = ::resolveShareable,
        extensionMime = { extension ->
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
        },
        hasHandler = ::hasHandler,
        start = { spec -> start(context, spec) },
    )

    fun perform(
        action: CompletedFileAction,
        identity: CompletedFileIdentity,
    ): CompletedFileActionResult = opener.perform(action, identity)

    private fun resolveShareable(identity: CompletedFileIdentity): ShareableCompletedFile? {
        return when (val destination = CompletedFileDestination.classify(identity.destinationPath)) {
            CompletedFileDestinationKind.Unavailable -> null
            is CompletedFileDestinationKind.ContentDocument -> {
                val uri = Uri.parse(destination.uriString)
                ShareableCompletedFile(
                    uriString = uri.toString(),
                    persistedMimeType = identity.persistedMimeType,
                    fileName = identity.fileName,
                    contentResolverType = contentType(appContext.contentResolver, uri),
                )
            }
            is CompletedFileDestinationKind.LocalFile -> {
                val file = destination.file
                if (!file.isFile || !file.canRead()) return null
                val uri = FileProvider.getUriForFile(appContext, authority(appContext.packageName), file)
                ShareableCompletedFile(
                    uriString = uri.toString(),
                    persistedMimeType = identity.persistedMimeType,
                    fileName = identity.fileName,
                    contentResolverType = contentType(appContext.contentResolver, uri),
                )
            }
        }
    }

    private fun hasHandler(spec: CompletedFileIntentSpec): Boolean {
        val intent = spec.toAndroidIntent()
        @Suppress("DEPRECATION")
        val resolved = appContext.packageManager.queryIntentActivities(
            intent,
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        return resolved.isNotEmpty()
    }

    private fun start(launchContext: Context, spec: CompletedFileIntentSpec) {
        val intent = spec.toAndroidIntent()
        val launched = if (spec.chooserTitle != null) {
            Intent.createChooser(intent, spec.chooserTitle).apply {
                clipData = intent.clipData
                addFlags(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            intent
        }
        if (launchContext !is Activity) {
            launched.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        launchContext.startActivity(launched)
    }

    companion object {
        const val AUTHORITY_SUFFIX = ".files"

        fun authority(packageName: String): String = "$packageName$AUTHORITY_SUFFIX"

        fun uriForFile(context: Context, file: File): Uri =
            FileProvider.getUriForFile(
                context.applicationContext,
                authority(context.packageName),
                file,
            )
    }
}

internal fun CompletedFileIntentSpec.toAndroidIntent(): Intent {
    val intent = Intent(action)
    if (dataUri != null) {
        intent.setDataAndType(Uri.parse(dataUri), mimeType)
    } else {
        intent.type = mimeType
    }
    categories.forEach { intent.addCategory(it) }
    intent.addFlags(flags)
    extraStreamUri?.let { stream ->
        intent.putExtra(Intent.EXTRA_STREAM, Uri.parse(stream))
    }
    clipDataUri?.let { clip ->
        intent.clipData = ClipData.newRawUri("SDM", Uri.parse(clip))
    }
    return intent
}

private fun contentType(contentResolver: ContentResolver, uri: Uri): String? = try {
    contentResolver.getType(uri)
} catch (_: SecurityException) {
    null
} catch (_: IllegalArgumentException) {
    null
} catch (_: Exception) {
    null
}
